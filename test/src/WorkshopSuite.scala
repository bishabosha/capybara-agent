package capybara.agent

import steps.result.Result

/** Generic text-file capability and capture checking through the production harness. */
class WorkshopSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(skill) => skill
    case Result.Err(error) => fail(error)

  private lazy val builtins = loaded("builtin-skills/capybara-builtins")

  private def withWorkspace(test: (Seq[Skill], os.Path) => Unit): Unit =
    val root = os.temp.dir(prefix = "capybara-text-files-")
    try
      os.write(root / "Greeting.scala", "val greeting = \"hello, world\"\n")
      os.write(root / "README.md", "Original documentation.\n")
      val original = loaded("skills/filesystem")
      val factory = s"capybara.skills.filesystem.internal.configured(${ujson.write(root.toString)})"
      val skill = original.copy(bindings = original.bindings.map(b =>
        (name = b.name, tpe = b.tpe, value = factory)))
      test(Seq(skill, loaded("skills/editing")), root)
    finally os.remove.all(root)

  private def run(skill: Seq[Skill], code: String): ReplExec.CodeOutput =
    ReplExec.runCode(builtins, skill, code, CancellationToken.Never) match
      case Result.Ok(output) => output
      case Result.Err(error) => fail(error)

  test("generic editing API lists, reads, creates and edits files of different formats") {
    withWorkspace { (skill, root) =>
      val output = run(skill, os.read(os.pwd / "examples/snippets/editing.scala"))
      assert(!output.failed, output.stderr)
      assertEquals(os.read(root / "Greeting.scala"), "val greeting = \"hello, capybara\"\n")
      assertEquals(os.read(root / "notes.txt"), "Edited through the editing capability.\n")
      val markdown = run(skill, "editing.edit(\"README.md\")(_.replace(\"Original\", \"Updated\"))")
      assert(!markdown.failed, markdown.stderr)
      assertEquals(os.read(root / "README.md"), "Updated documentation.\n")
      val json = run(skill, "filesystem.write(\"config.json\", \"{}\\n\"); puts(filesystem.read(\"config.json\"))")
      assert(!json.failed, json.stderr)
      assertEquals(ujson.read(json.stdout)("special.success").str, "{}\n")
      os.write(root / "run.sh", "#!/bin/sh\n")
      os.perms.set(root / "run.sh", "rwxr-xr-x")
      val script = run(skill, "editing.edit(\"run.sh\")(_ + \"echo done\\n\")")
      assert(!script.failed, script.stderr)
      assert(java.nio.file.Files.isExecutable(java.nio.file.Path.of((root / "run.sh").toString)))
    }
  }

  test("workspace boundaries and stale replacement checks are implemented by the file capability") {
    withWorkspace { (skill, root) =>
      os.symlink(root / "outside", root / os.up)
      for (code, expected) <- Seq(
        "filesystem.read(\"../credentials\")" -> "PATH_DENIED",
        "filesystem.read(\"/etc/passwd\")" -> "PATH_DENIED",
        "filesystem.write(\"outside/escape.txt\", \"x\")" -> "PATH_DENIED",
        "editing.replace(\"README.md\", \"stale\", \"new\")" -> "CONTENT_CHANGED"
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
        "editing.edit(\"README.md\")(text => { puts(text); text })",
        "editing.edit(\"README.md\")(text => filesystem.read(\"Greeting.scala\"))",
        "editing.edit(\"README.md\")(text => { filesystem.write(\"side.txt\", text); text })",
        "capybara.skills.editing.internal.impl()",
        "puts(sys.env(\"CAPYBARA_API_KEY\"))",
        "new java.lang.ProcessBuilder(\"env\").start()",
        "puts(java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\")))"
      ) do
        assert(ReplExec.runCode(builtins, skill, code, CancellationToken.Never).isInstanceOf[Result.Err[?]], code)
      assert(!os.exists(root / "side.txt"))
      assertEquals(os.read(root / "README.md"), "Original documentation.\n")
    }
  }

  test("filesystem works alone and editing requires the caller's file authority") {
    withWorkspace { (skills, root) =>
      val filesystem = skills.head
      val editor = skills(1)
      val output = run(Seq(filesystem), "filesystem.write(\"standalone.txt\", \"hello\"); puts(filesystem.read(\"standalone.txt\"))")
      assert(!output.failed, output.stderr)
      assertEquals(os.read(root / "standalone.txt"), "hello")
      val withoutFiles = ReplExec.runCode(builtins, Seq(editor.copy(requires = Nil)),
        "editing.edit(\"standalone.txt\")(_ + \"!\")", CancellationToken.Never)
      withoutFiles match
        case Result.Err(error) => assert(error.contains("FileSystem"), error)
        case Result.Ok(value) => fail("Editor compiled without file authority: " + value)
      assertEquals(os.read(root / "standalone.txt"), "hello")
    }
  }

  test("read deliberately releases ordinary printable text") {
    withWorkspace { (skill, root) =>
      os.write(root / "example.txt", "LAB_ONLY_CANARY_EXAMPLE")
      val output = run(skill, "puts(filesystem.read(\"example.txt\"))")
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
    assert(ScalaAgent.availableSkills.exists(_.universe == "editing"))
  }
