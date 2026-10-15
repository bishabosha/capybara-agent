package capybara.agent

import steps.result.Result

/** Exercises the public skill through the same safe compiler and trusted harness as the agent. */
class WorkshopSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(skill) => skill
    case Result.Err(error) => fail(error)

  private lazy val builtins = loaded("builtin-skills/capybara-builtins")
  private val python = "/usr/bin/python3"
  private lazy val node = os.proc("/usr/bin/which", "node").call().out.text().trim

  private def withWorkshop(test: (Skill, os.Path) => Unit): Unit =
    val parent = os.temp.dir(prefix = "capybara-workshop-suite-")
    val root = parent / "repo"
    try
      val setup = os.proc(python, "scripts/workshop.py", "init", "--repo", root.toString).call()
      assert(ujson.read(setup.out.text())("ok").bool)
      val original = loaded("skills/coding")
      val factory = s"workshop.internal.configured(${ujson.write(root.toString)}, ${ujson.write(node)}, ${ujson.write(python)})"
      val skill = original.copy(bindings = original.bindings.map(b => (name = b.name, tpe = b.tpe, value = factory)))
      test(skill, root)
    finally os.remove.all(parent)

  private def run(skill: Skill, code: String): ReplExec.CodeOutput =
    ReplExec.runCode(builtins, skill, code) match
      case Result.Ok(output) => output
      case Result.Err(error) => fail(error)

  test("workshop policy and real sandbox probes") {
    assume(System.getProperty("os.name").contains("Mac"))
    val result = os.proc(python, "scripts/test_workshop.py").call(check = false, timeout = 60000)
    assertEquals(result.exitCode, 0, result.out.text() + result.err.text())
  }

  test("coding universe compiles in safe mode and completes the word-count workflow") {
    assume(System.getProperty("os.name").contains("Mac"))
    withWorkshop { (skill, root) =>
      val baseline = run(skill, "puts(coding.test())")
      assert(!baseline.failed)
      assertEquals(ujson.read(baseline.stdout)("special.success")("status").str, "failed")
      val fix = """export function countWords(text) {
                |  const clean = text.trim();
                |  return clean === '' ? 0 : clean.split(/\s+/u).length;
                |}
                |""".stripMargin
      val code = s"""val before = coding.read("src/word-count.mjs")
                   |coding.replace("src/word-count.mjs", before, ${ujson.write(fix)})
                   |puts(coding.test())
                   |puts(coding.diff())
                   |puts(coding.sandboxChecks())
                   |""".stripMargin
      val output = run(skill, code)
      assert(!output.failed, output.stderr)
      val lines = output.stdout.linesIterator.map(ujson.read(_)("special.success")).toVector
      assertEquals(lines.head("status").str, "passed")
      assert(lines(1).str.contains("+  const clean"))
      assertEquals(lines(2).arr.size, 5)
      assertEquals(os.read(root / "src/word-count.mjs"), fix)
    }
  }

  test("coding policy failures are fixed errors and trusted factories are rejected") {
    withWorkshop { (skill, _) =>
      for (code, expected) <- Seq(
        "coding.read(\"../credentials\")" -> "PATH_DENIED",
        "coding.replace(\".github/workflows/ci.yml\", \"\", \"\")" -> "WRITE_DENIED",
        "coding.replace(\"src/word-count.mjs\", coding.read(\"src/word-count.mjs\"), \"LAB_ONLY_CANARY_7F3A\")" -> "CONTENT_DENIED"
      ) do
        val output = run(skill, code)
        assert(output.failed)
        assert(output.stderr.contains(expected))
        assert(!output.stderr.contains("LAB_ONLY_CANARY_7F3A"))
      val factory = ReplExec.runCode(builtins, skill, "workshop.internal.impl()")
      factory match
        case Result.Err(error) => assert(error.contains("rejectSafe"))
        case _ => fail("Trusted coding factory was available to safe code")
    }
  }

  test("transcript includes every submitted command and separates host invocation") {
    val filesystem = loaded("skills/filesystem")
    val text = SkillTranscript.render(builtins, filesystem, "puts(filesystem.pwd())", os.pwd)
    val expected = Seq(
      "/capybara-builtins/sigs.jar", "/capybara-builtins/impl.jar", builtins.predef.get,
      "/filesystem/sigs.jar", "/filesystem/impl.jar", ":settings -language:experimental.safe",
      "def agentCode_", "puts(filesystem.pwd())", ":settings -language:experimental.captureChecking",
      "def harnessEntry_", "val __capybara_output", "val __capybara_filesystem", "// HOST invocation"
    )
    val positions = expected.map(text.indexOf(_))
    assert(positions.forall(_ >= 0))
    assertEquals(positions, positions.sorted)
    assert(SkillTranscript.render(builtins, filesystem, "puts(1)\n\n").contains("puts(1)\n\n\n()"))
    assertEquals(SkillTranscript.render(builtins, Skills.Basic, "puts(1)").linesIterator.count(_.startsWith(":jar")), 2)
    assertEquals(SkillTranscript.render(builtins, builtins, "puts(1)").linesIterator.count(_.startsWith(":jar")), 2)
    assert(ScalaAgent.availableSkills.exists(_.universe == "coding"))
  }

  test("pure coding transformations reject captured output and coding capabilities") {
    withWorkshop { (skill, _) =>
      for code <- Seq(
        "coding.transformSource(source => { puts(source); source })",
        "coding.transformSource(source => coding.read(\"README.md\"))"
      ) do
        assert(ReplExec.runCode(builtins, skill, code).isInstanceOf[Result.Err[?]])
      val code = os.read(os.pwd / "examples/snippets/coding.scala")
      val output = run(skill, code)
      assert(!output.failed, output.stderr)
      val values = output.stdout.linesIterator.map(ujson.read(_)("special.success")).toVector
      assertEquals(values(1)("status").str, "failed")
      assertEquals(values(2)("status").str, "passed")
    }
  }
