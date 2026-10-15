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
      """{"universes":["coding","filesystem"],"scala_code":"()"}""")
    assertEquals(modern.requestedUniverses, Seq("coding", "filesystem"))
    val legacy = upickle.default.read[ScalaAgent.ScalaToolCall](
      """{"universe":"basic","scala_code":"puts(1)"}""")
    assertEquals(legacy.requestedUniverses, Seq("basic"))
  }

  test("dependency resolution orders capabilities, deduplicates and rejects invalid graphs") {
    val coding = loaded("skills/coding")
    val filesystem = loaded("skills/filesystem")
    val dependent = filesystem.copy(requires = Seq("coding"))
    assertEquals(Skills.resolve(Seq("filesystem", "coding", "filesystem"), Seq(dependent, coding), builtins),
      Result.Ok(Vector(coding, dependent)))
    val typeOnly = filesystem.copy(apiDependencies = Seq("coding"))
    assertEquals(Skills.resolve(Seq("filesystem"), Seq(typeOnly, coding), builtins), Result.Ok(Vector(typeOnly)))
    assertEquals(Skills.resolve(Seq("filesystem"), Seq(typeOnly, coding), builtins, includeApis = true),
      Result.Ok(Vector(coding, typeOnly)))
    assert(Skills.resolve(Seq("missing"), Seq(coding), builtins).isInstanceOf[Result.Err[?]])
    assert(Skills.resolve(Seq("coding"), Seq(coding.copy(requires = Seq("coding"))), builtins)
      .isInstanceOf[Result.Err[?]])
    assert(Skills.resolve(Seq("coding", "filesystem"), Seq(coding,
      filesystem.copy(bindings = coding.bindings)), builtins).isInstanceOf[Result.Err[?]])
  }

  test("a dependent skill uses a locally published safe API and the harness injects both capabilities") {
    val root = os.temp.dir(prefix = "capybara-composition-")
    try
      os.write(root / "note.txt", "hello")
      val coding = loaded("skills/coding")
      val configured = coding.copy(bindings = coding.bindings.map(b =>
        (name = b.name, tpe = b.tpe, value = s"editing.internal.configured(${ujson.write(root.toString)})")))
      val adapter = Skill(name = "composition-test", description = "Read through the safe editing API",
        universe = "composition-test", interface = """//> using repository ivy2local
          |//> using dep "dev.capybara.skills::coding-api:0.1.0-SNAPSHOT"
          |package composition
          |import scala.language.experimental.captureChecking
          |trait ReadNote extends caps.SharedCapability { def note()(using editing.TextFiles): String }
          |""".stripMargin,
        code = """//> using repository ivy2local
          |//> using dep "dev.capybara.skills::coding-api:0.1.0-SNAPSHOT"
          |package composition.internal
          |import scala.language.experimental.captureChecking
          |@caps.rejectSafe
          |def impl(): composition.ReadNote = new composition.ReadNote {
          |  def note()(using files: editing.TextFiles): String = files.read("note.txt")
          |}
          |""".stripMargin,
        api = "Read a note", predef = None,
        bindings = Seq((name = "notes", tpe = "composition.ReadNote", value = "composition.internal.impl()")),
        requires = Seq("coding"))
      val output = ReplExec.runCode(builtins, Seq(adapter, configured),
        "coding.edit(\"note.txt\")(_ + \"!\"); puts(notes.note())", CancellationToken.Never) match
        case Result.Ok(value) => value
        case Result.Err(error) => fail(error)
      assert(!output.failed, output.stderr)
      assertEquals(ujson.read(output.stdout)("special.success").str, "hello!")
      val rejected = ReplExec.runCode(builtins, Seq(adapter, configured),
        "coding.edit(\"note.txt\")(text => notes.note())", CancellationToken.Never)
      assert(rejected.isInstanceOf[Result.Err[?]])
      assertEquals(SkillArtifacts.dependencies(adapter.code), Seq("coding"))
    finally os.remove.all(root)
  }

  test("skill-publish publishes dependency APIs first and prints usable directives") {
    val bytes = new java.io.ByteArrayOutputStream()
    scala.Console.withOut(bytes) {
      capybara.agent.run("skill-publish", "--skill", "github")
    }
    val output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
    assert(output.contains("//> using repository ivy2local"))
    val coding = output.indexOf("dev.capybara.skills::coding-api:0.1.0-SNAPSHOT")
    val github = output.indexOf("dev.capybara.skills::github-api:0.1.0-SNAPSHOT")
    assert(coding >= 0 && github > coding, output)
    assert(!output.contains("CAPYBARA_GITHUB_TOKEN"))
  }
