package capybara.agent

import steps.result.Result

class SkillCompositionSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(value) => value
    case Result.Err(error) => fail(error)
  private lazy val builtins = loaded("builtin-skills/capybara-builtins")

  test("tool arguments support arrays and legacy single-universe calls") {
    val modern = upickle.default.read[ScalaAgent.ScalaToolCall](
      """{"universes":["editing","filesystem"],"scala_code":"()"}""")
    assertEquals(modern.requestedUniverses, Seq("editing", "filesystem"))
    val legacy = upickle.default.read[ScalaAgent.ScalaToolCall](
      """{"universe":"basic","scala_code":"puts(1)"}""")
    assertEquals(legacy.requestedUniverses, Seq("basic"))
  }

  test("dependency resolution orders capabilities, deduplicates and rejects invalid graphs") {
    val filesystem = loaded("skills/filesystem")
    val editing = loaded("skills/editing")
    assertEquals(editing.requires, Seq("filesystem"))
    assertEquals(editing.apiDependencies, Seq("filesystem"))
    assertEquals(Skills.resolve(Seq("editing", "filesystem", "editing"), Seq(editing, filesystem), builtins),
      Result.Ok(Vector(filesystem, editing)))
    val typeOnly = editing.copy(requires = Nil)
    assertEquals(Skills.resolve(Seq("editing"), Seq(typeOnly, filesystem), builtins), Result.Ok(Vector(typeOnly)))
    assertEquals(Skills.resolve(Seq("editing"), Seq(typeOnly, filesystem), builtins, includeApis = true),
      Result.Ok(Vector(filesystem, typeOnly)))
    assert(Skills.resolve(Seq("missing"), Seq(editing), builtins).isInstanceOf[Result.Err[?]])
    assert(Skills.resolve(Seq("editing"), Seq(editing, filesystem.copy(requires = Seq("editing"))), builtins)
      .isInstanceOf[Result.Err[?]])
    assert(Skills.resolve(Seq("editing", "filesystem"), Seq(editing,
      filesystem.copy(bindings = editing.bindings)), builtins).isInstanceOf[Result.Err[?]])
  }

  test("a dependent skill uses a locally published safe API and the harness injects both capabilities") {
    val root = os.temp.dir(prefix = "capybara-composition-")
    try
      os.write(root / "note.txt", "hello")
      val filesystem = loaded("skills/filesystem")
      val editing = loaded("skills/editing")
      val configured = filesystem.copy(bindings = filesystem.bindings.map(b =>
        (name = b.name, tpe = b.tpe, value = s"capybara.skills.filesystem.internal.configured(${ujson.write(root.toString)})")))
      val adapter = Skill(name = "composition-test", description = "Read through the safe filesystem API",
        universe = "composition-test", interface = """//> using repository ivy2local
          |//> using dep "dev.capybara.skills::filesystem-api:0.1.0-SNAPSHOT"
          |package composition
          |import scala.language.experimental.captureChecking
          |trait ReadNote extends caps.SharedCapability { def note()(using capybara.skills.filesystem.FileSystem): String }
          |""".stripMargin,
        code = """//> using repository ivy2local
          |//> using dep "dev.capybara.skills::filesystem-api:0.1.0-SNAPSHOT"
          |package composition.internal
          |import scala.language.experimental.captureChecking
          |@caps.rejectSafe
          |def impl(): composition.ReadNote = new composition.ReadNote {
          |  def note()(using files: capybara.skills.filesystem.FileSystem): String = files.read("note.txt")
          |}
          |""".stripMargin,
        api = "Read a note", predef = None,
        bindings = Seq((name = "notes", tpe = "composition.ReadNote", value = "composition.internal.impl()")),
        requires = Seq("filesystem"))
      val output = ReplExec.runCode(builtins, Seq(adapter, configured, editing),
        "editing.edit(\"note.txt\")(_ + \"!\"); puts(notes.note())", CancellationToken.Never) match
        case Result.Ok(value) => value
        case Result.Err(error) => fail(error)
      assert(!output.failed, output.stderr)
      assertEquals(ujson.read(output.stdout)("special.success").str, "hello!")
      val rejected = ReplExec.runCode(builtins, Seq(adapter, configured, editing),
        "editing.edit(\"note.txt\")(text => notes.note())", CancellationToken.Never)
      assert(rejected.isInstanceOf[Result.Err[?]])
      assertEquals(SkillArtifacts.dependencies(adapter.code), Seq("filesystem"))
    finally os.remove.all(root)
  }

  test("skill-publish publishes dependency APIs first and prints usable directives") {
    val bytes = new java.io.ByteArrayOutputStream()
    scala.Console.withOut(bytes) {
      capybara.agent.run("skill-publish", "--skill", "github")
    }
    val output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
    assert(output.contains("//> using repository ivy2local"))
    val filesystem = output.indexOf("dev.capybara.skills::filesystem-api:0.1.0-SNAPSHOT")
    val github = output.indexOf("dev.capybara.skills::github-api:0.1.0-SNAPSHOT")
    assert(filesystem >= 0 && github > filesystem, output)
    assert(!output.contains("CAPYBARA_GITHUB_TOKEN"))
  }
