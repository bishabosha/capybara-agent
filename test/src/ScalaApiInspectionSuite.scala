package capybara.agent

import steps.result.Result
import ReplExec.ScalaToolResult

class ScalaApiInspectionSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")
  private def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
    case Result.Ok(skill) => skill
    case Result.Err(error) => fail(error)
  private lazy val builtins = loaded("builtin-skills/capybara-builtins")

  test("qualified references distinguish duplicate names and preserve nested types, aliases and type parameters") {
    val fixture = Skill(name = "qualified-fixture", universe = "qualified-fixture", description = "Type-reference fixture",
      interface = """package referenceleft {
        |  case class Issue(number: Int)
        |  trait Box[A] { def value: A }
        |  object Domain { case class Nested(value: String) }
        |  type Alias = Issue
        |  trait Api {
        |    def left: referenceleft.Issue
        |    def right: referenceright.Issue
        |    def nested: referenceleft.Domain.Nested
        |    def alias: referenceleft.Alias
        |    def boxed: referenceleft.Box[String]
        |  }
        |}
        |package referenceright { case class Issue(value: String) }
        |""".stripMargin,
      code = "package referencefixture.internal\nimport scala.language.experimental.captureChecking\n@caps.rejectSafe\ndef impl(): Unit = ()\n",
      api = "", predef = None)
    ReplExec.prepareSkills(builtins, Seq(fixture)) match
      case Result.Ok(_) => ()
      case Result.Err(error) => fail(error)
    val runtime = os.pwd / ".agent-runtime/cellar"
    CellarEnvironment.prepare(os.pwd)
    val javaExecutable = os.Path(sys.props("java.home")) / "bin/java"
    def inspect(symbol: String): String =
      val result = os.proc(javaExecutable.toString, "-cp", os.read(runtime / "classpath.txt"),
        "cellar.cli.CellarApp", "get-external", "dev.capybara.skills:qualified-fixture-api_3:0.1.0-SNAPSHOT", symbol)
        .call(cwd = runtime, check = false, timeout = 30000, stdout = os.Pipe, stderr = os.Pipe)
      assertEquals(result.exitCode, 0, result.err.text())
      result.out.text()
    val api = inspect("referenceleft.Api")
    assert(api.contains("def left: referenceleft.Issue"), api)
    assert(api.contains("def right: referenceright.Issue"), api)
    assert(api.contains("def nested: referenceleft.Domain.Nested"), api)
    assert(api.contains("def alias: referenceleft.Alias"), api)
    assert(api.contains("def boxed: referenceleft.Box[String]"), api)
    assert(inspect("referenceleft.Box").contains("def value: A"))
    assert(inspect("referenceleft.Domain.Nested").contains("Nested"))
    assert(inspect("referenceleft.Alias").contains("referenceleft.Issue"))
  }

  test("the managed Cellar reads actual Scala 3.10 GitHub API JARs including contextual parameters") {
    ReplExec.prepareSkills(builtins, Seq(loaded("skills/github"))) match
      case Result.Ok(_) => ()
      case Result.Err(error) => fail(error)
    val code = """puts(inspectScalaApi.get("github", "capybara.skills.github.GitHub"))
      |puts(inspectScalaApi.list("github", "capybara.skills.github"))
      |puts(inspectScalaApi.search("github", "GitHub"))
      |puts(inspectScalaApi.get("github", "capybara.skills.github.Issue"))
      |puts(inspectScalaApi.get("github", "capybara.skills.filesystem.FileSystem"))
      |""".stripMargin
    val output = ReplExec.runCode(builtins, loaded("skills/inspect-scala-api"), code) match
      case Result.Ok(value) => ReplExec.decodeOutput(value)
      case Result.Err(error) => fail(error)
    output match
      case ScalaToolResult.Success(stdout, _) =>
        assertEquals(stdout.size, 5)
        val api = stdout.head.str
        assert(api.contains("lookupIssue(number: Int): capybara.skills.github.Issue"))
        assert(api.contains("using files: capybara.skills.filesystem.FileSystem"))
        assert(api.contains("branch: capybara.skills.github.Branch"))
        assert(api.contains("GitHub authority only"))
        assert(!api.contains("wrong version"))
        assert(!api.contains("getIssue"))
        assert(!api.contains("internal.impl"))
        assert(stdout(1).str.contains("GitHub"))
        assert(stdout(2).str.contains("GitHub"))
        assert(stdout(3).str.contains("## capybara.skills.github.Issue"))
        assert(stdout(4).str.contains("## capybara.skills.filesystem.FileSystem"))
      case other => fail(s"Could not inspect the real skill JAR: $other")
  }

  test("Cellar setup pins compatible readers, caches the runtime and retries interrupted setup") {
    val root = os.temp.dir(prefix = "capybara-cellar-runtime-")
    try
      val jar = root / "dependency.jar"
      os.write(jar, "fixture")
      val calls = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
      def run(arguments: Seq[String], directory: os.Path): String =
        assertEquals(directory, root / ".agent-runtime/cellar")
        calls += arguments
        if arguments.head == "cs" then jar.toString else "telemetry disabled"
      CellarEnvironment.prepareWith(root, run)
      assertEquals(calls.size, 2)
      assert(calls.head.contains(CellarEnvironment.Reader))
      assert(calls.head.contains(s"org.scala-lang:scala3-tasty-inspector_3:${ReplExec.ScalaVersion}"))
      assertEquals(calls(1).takeRight(2), Seq("telemetry", "disable"))
      CellarEnvironment.prepareWith(root, run)
      assertEquals(calls.size, 2)
      val _ = os.remove(root / ".agent-runtime/cellar/ready")
      val _ = intercept[IllegalStateException] {
        CellarEnvironment.prepareWith(root, (_, _) => throw IllegalStateException("interrupted"))
      }
      assert(!os.exists(root / ".agent-runtime/cellar/ready"))
      CellarEnvironment.prepareWith(root, run)
      assertEquals(calls.size, 4)
    finally os.remove.all(root)
  }

  test("Cellar capability fixes the API coordinate and passes queries as literal process arguments") {
    val root = os.temp.dir(prefix = "capybara-cellar-test-")
    try
      os.write(root / "skills/editing/SKILL.md", "installed", createFolders = true)
      os.write(root / ".agent-runtime/interface/editing/sigs.jar", "prepared", createFolders = true)
      val executable = root / "fake-cellar"
      os.write(executable, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
      assert(executable.toIO.setExecutable(true))
      val skill = loaded("skills/inspect-scala-api")
      val configured = skill.copy(bindings = skill.bindings.map(b =>
        (name = b.name, tpe = b.tpe,
          value = s"capybara.skills.inspectscalaapi.internal.configured(${ujson.write(executable.toString)}, ${ujson.write(root.toString)})")))
      val code = """puts(inspectScalaApi.get("editing", "capybara.skills.editing.TextEditor"))
        |puts(inspectScalaApi.search("editing", "name; $(touch SHOULD_NOT_EXIST)"))
        |puts(try { inspectScalaApi.get("../editing", "capybara.skills.editing.TextEditor"); "accepted" }
        |     catch { case _: IllegalArgumentException => "invalid skill rejected" })
        |puts(try { inspectScalaApi.get("unknown", "example.Type"); "accepted" }
        |     catch { case _: IllegalArgumentException => "unknown skill rejected" })
        |puts(try { inspectScalaApi.get("editing", "--help"); "accepted" }
        |     catch { case _: IllegalArgumentException => "option rejected" })
        |""".stripMargin
      val output = ReplExec.runCode(builtins, configured, code) match
        case Result.Ok(value) => value
        case Result.Err(error) => fail(error)
      ReplExec.decodeOutput(output) match
        case ScalaToolResult.Success(stdout, _) =>
          assert(stdout.head.str.contains("get-external\ndev.capybara.skills:editing-api_3:0.1.0-SNAPSHOT\ncapybara.skills.editing.TextEditor"))
          assert(stdout(1).str.contains("search-external\n--limit\n20"))
          assert(stdout(1).str.contains("name; $(touch SHOULD_NOT_EXIST)"))
          assertEquals(stdout.drop(2).map(_.str), Vector("invalid skill rejected", "unknown skill rejected", "option rejected"))
        case other => fail(s"Unexpected result: $other")
      assert(!os.exists(root / "SHOULD_NOT_EXIST"))
    finally os.remove.all(root)
  }
