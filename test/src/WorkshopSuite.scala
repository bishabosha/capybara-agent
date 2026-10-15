package capybara.agent

import steps.result.Result

/** Generic text-file capability and capture checking through the production harness. */
class WorkshopSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(skill) => skill
    case Result.Err(error) => fail(error)

  private lazy val builtins = loaded("builtin-skills/capybara-builtins")

  private def withWorkspace(test: (Skill, os.Path) => Unit): Unit =
    val root = os.temp.dir(prefix = "capybara-text-files-")
    try
      os.write(root / "Greeting.scala", "val greeting = \"hello, world\"\n")
      os.write(root / "README.md", "Original documentation.\n")
      val original = loaded("skills/coding")
      val factory = s"editing.internal.configured(${ujson.write(root.toString)})"
      val skill = original.copy(bindings = original.bindings.map(b =>
        (name = b.name, tpe = b.tpe, value = factory)))
      test(skill, root)
    finally os.remove.all(root)

  private def run(skill: Skill, code: String): ReplExec.CodeOutput =
    ReplExec.runCode(builtins, skill, code) match
      case Result.Ok(output) => output
      case Result.Err(error) => fail(error)

  test("generic coding API lists, reads, creates and edits files of different formats") {
    withWorkspace { (skill, root) =>
      val output = run(skill, os.read(os.pwd / "examples/snippets/coding.scala"))
      assert(!output.failed, output.stderr)
      assertEquals(os.read(root / "Greeting.scala"), "val greeting = \"hello, capybara\"\n")
      assertEquals(os.read(root / "notes.txt"), "Edited through the coding capability.\n")
      val markdown = run(skill, "coding.edit(\"README.md\")(_.replace(\"Original\", \"Updated\"))")
      assert(!markdown.failed, markdown.stderr)
      assertEquals(os.read(root / "README.md"), "Updated documentation.\n")
      val json = run(skill, "coding.write(\"config.json\", \"{}\\n\"); puts(coding.read(\"config.json\"))")
      assert(!json.failed, json.stderr)
      assertEquals(ujson.read(json.stdout)("special.success").str, "{}\n")
      os.write(root / "run.sh", "#!/bin/sh\n")
      os.perms.set(root / "run.sh", "rwxr-xr-x")
      val script = run(skill, "coding.edit(\"run.sh\")(_ + \"echo done\\n\")")
      assert(!script.failed, script.stderr)
      assert(java.nio.file.Files.isExecutable(java.nio.file.Path.of((root / "run.sh").toString)))
    }
  }

  test("workspace boundaries and stale replacement checks are implemented by the file capability") {
    withWorkspace { (skill, root) =>
      os.symlink(root / "outside", root / os.up)
      for (code, expected) <- Seq(
        "coding.read(\"../credentials\")" -> "PATH_DENIED",
        "coding.read(\"/etc/passwd\")" -> "PATH_DENIED",
        "coding.write(\"outside/escape.txt\", \"x\")" -> "PATH_DENIED",
        "coding.replace(\"README.md\", \"stale\", \"new\")" -> "CONTENT_CHANGED"
      ) do
        val output = run(skill, code)
        assert(output.failed)
        assert(output.stderr.contains(expected))
      assertEquals(os.read(root / "README.md"), "Original documentation.\n")
    }
  }

  test("capture checking rejects extra capabilities and safe mode rejects ambient effects") {
    withWorkspace { (skill, root) =>
      for code <- Seq(
        "coding.edit(\"README.md\")(text => { puts(text); text })",
        "coding.edit(\"README.md\")(text => coding.read(\"Greeting.scala\"))",
        "coding.edit(\"README.md\")(text => { coding.write(\"side.txt\", text); text })",
        "editing.internal.impl()",
        "puts(sys.env(\"CAPYBARA_API_KEY\"))",
        "new java.lang.ProcessBuilder(\"env\").start()",
        "puts(java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\")))"
      ) do
        assert(ReplExec.runCode(builtins, skill, code).isInstanceOf[Result.Err[?]], code)
      assert(!os.exists(root / "side.txt"))
      assertEquals(os.read(root / "README.md"), "Original documentation.\n")
    }
  }

  test("read deliberately releases ordinary printable text") {
    withWorkspace { (skill, root) =>
      os.write(root / "example.txt", "LAB_ONLY_CANARY_EXAMPLE")
      val output = run(skill, "puts(coding.read(\"example.txt\"))")
      assert(!output.failed, output.stderr)
      assertEquals(ujson.read(output.stdout)("special.success").str, "LAB_ONLY_CANARY_EXAMPLE")
    }
  }

  test("transcript includes submitted commands and separates host invocation") {
    val filesystem = loaded("skills/filesystem")
    val text = SkillTranscript.render(builtins, filesystem, "puts(filesystem.pwd())", os.pwd)
    val expected = Seq("/capybara-builtins/sigs.jar", "/capybara-builtins/impl.jar", builtins.predef.get,
      "/filesystem/sigs.jar", "/filesystem/impl.jar", ":settings -language:experimental.safe",
      "def agentCode_", "puts(filesystem.pwd())", ":settings -language:experimental.captureChecking",
      "def harnessEntry_", "given __capybara_output", "given __capybara_filesystem", "// HOST invocation")
    val positions = expected.map(text.indexOf(_))
    assert(positions.forall(_ >= 0))
    assertEquals(positions, positions.sorted)
    assert(SkillTranscript.render(builtins, filesystem, "puts(1)\n\n").contains("puts(1)\n\n\n()"))
    assertEquals(SkillTranscript.render(builtins, Skills.Basic, "puts(1)").linesIterator.count(_.startsWith(":jar")), 2)
    assertEquals(SkillTranscript.render(builtins, builtins, "puts(1)").linesIterator.count(_.startsWith(":jar")), 2)
    assert(ScalaAgent.availableSkills.exists(_.universe == "coding"))
  }
