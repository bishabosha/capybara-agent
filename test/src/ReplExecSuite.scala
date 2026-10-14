package capybara.agent

import steps.result.Result
import ReplExec.{ScalaToolResult, CodeOutput}

class ReplExecSuite extends munit.FunSuite:
  private def loaded(directory: String): Skill =
    Skills.loadFrom(os.pwd / os.RelPath(directory)) match
      case Result.Ok(skill) => skill
      case Result.Err(error) => fail(error)

  private lazy val builtins = loaded("builtin-skills/capybara-builtins")

  private def withSnippet(code: String)(test: String => Unit): Unit =
    val path = java.nio.file.Files.createTempFile("capybara-snippet-", ".scala")
    try
      val _ = java.nio.file.Files.writeString(path, code)
      test(path.toString)
    finally
      val _ = java.nio.file.Files.deleteIfExists(path)

  private def execute(code: String, skill: Skill = Skills.Basic): CodeOutput =
    ReplExec.runCode(builtins, skill, code) match
      case Result.Ok(output) => output
      case Result.Err(error) => fail(error)

  test("final values produce no output") {
    assertEquals(execute("List(1, 2, 3).sum"), CodeOutput("", ""))
    assertEquals(ReplExec.decodeOutput(CodeOutput("", "")), ScalaToolResult.NoOutput)
  }

  test("explicit output is structured and final values are discarded") {
    val output = execute("puts(List(1, 2, 3).sum); 99")
    assertEquals(output.stdout.linesIterator.size, 1)
    assertEquals(ujson.read(output.stdout)("special.success"), ujson.Num(6))
    assertEquals(ReplExec.decodeOutput(output), ScalaToolResult.Success(Vector(ujson.Num(6)), Vector.empty))
  }

  test("multiple prints preserve order and escaped newlines") {
    val output = execute("""puts("first\nline"); puts(List(2, 3))""")
    assertEquals(output.stdout.linesIterator.size, 2)
    assertEquals(
      ReplExec.decodeOutput(output),
      ScalaToolResult.Success(Vector(ujson.Str("first\nline"), ujson.Arr(2, 3)), Vector.empty)
    )
  }

  test("printing an empty collection is distinguishable from no output") {
    assertEquals(
      ReplExec.decodeOutput(execute("puts(List.empty[Int])")),
      ScalaToolResult.Success(Vector(ujson.Arr()), Vector.empty)
    )
  }

  test("uncaught runtime exceptions are reported without an explicit print") {
    val output = execute("val zero = 0; 1 / zero")
    val error = ujson.read(output.stderr)("special.error")
    assertEquals(error("exception")("_runtimeClass").str, "java.lang.ArithmeticException")
    assertEquals(ReplExec.decodeOutput(output), ScalaToolResult.Failure(error, Vector.empty, Vector(error)))
  }

  test("output preceding an exception remains on stdout and execution fails") {
    val output = execute("puts(7); val zero = 0; 1 / zero")
    val lines = output.stdout.linesIterator.toVector
    assertEquals(lines.size, 1)
    assertEquals(output.stderr.linesIterator.size, 1)
    assertEquals(ujson.read(lines.head)("special.success"), ujson.Num(7))
    assert(ReplExec.decodeOutput(output).isInstanceOf[ScalaToolResult.Failure])
  }

  test("compilation failures remain failures and the next call starts fresh") {
    val failure = ReplExec.runCode(builtins, Skills.Basic, "missingSkillBinding.operation()")
    assert(failure.isInstanceOf[Result.Err[?]])
    assertEquals(execute("42"), CodeOutput("", ""))
    assertEquals(ReplExec.decodeOutput(execute("puts(8)")), ScalaToolResult.Success(Vector(ujson.Num(8)), Vector.empty))
  }

  test("skill predefs are available to handwritten snippets") {
    val output = execute("puts(filesystem.list(filesystem.pwd()).size)", loaded("skills/filesystem"))
    assert(ujson.read(output.stdout)("special.success").num > 0)
  }

  test("developer runner loads a snippet without the agent") {
    withSnippet("puts(List(1, 2, 3).sum)") { path =>
      SkillRunner.run("basic", path) match
        case Result.Ok(output) => assertEquals(ujson.read(output.stdout)("special.success"), ujson.Num(6))
        case Result.Err(error) => fail(error)
      assert(SkillRunner.run("basic", path + ".missing").isInstanceOf[Result.Err[?]])
    }
  }

  test("Builtins can be selected directly without duplicate JARs or capability bindings") {
    withSnippet("""puts("hello"); putsErr("warning")""") { path =>
      SkillRunner.run("builtin-skills/capybara-builtins", path) match
        case Result.Ok(output) =>
          assertEquals(
            ReplExec.decodeOutput(output),
            ScalaToolResult.Success(Vector(ujson.Str("hello")), Vector(ujson.Str("warning")))
          )
        case Result.Err(error) => fail(error)
    }
  }

  test("the filesystem lists paths through the developer runner") {
    withSnippet("puts(filesystem.list(filesystem.pwd()))") { path =>
      SkillRunner.run("skills/filesystem", path) match
        case Result.Ok(output) =>
          assert(!output.failed)
          assertEquals(output.stderr, "")
          val paths = ujson.read(output.stdout)("special.success").arr.map(_.str)
          assert(paths.exists(_.endsWith("README.md")))
        case Result.Err(error) => fail(error)
    }
  }

  test("skill-run command dispatches and emits only structured output") {
    assert(mainargs.ParserForMethods(capybara.agent).helpText().contains("--query"))
    val bytes = new java.io.ByteArrayOutputStream()
    val stream = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)
    withSnippet("puts(List(1, 2, 3).sum)") { path =>
      scala.Console.withOut(stream) {
        capybara.agent.run("skill-run", "--skill", "basic", "--file", path)
      }
    }
    assertEquals(ujson.read(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))("special.success"), ujson.Num(6))
  }

  test("system prompt and basic examples explain explicit output") {
    assert(ScalaAgent.SystemPrompt.contains("puts(value)"))
    assert(Skills.Basic.api.contains("puts(List(1, 2, 3).sum)"))
    assert(!ScalaAgent.SystemPrompt.contains("Return the desired answer as the final expression"))
  }

  test("safe code receives output but cannot manufacture another output capability") {
    val result = ReplExec.runCode(builtins, Skills.Basic, "builtins.outputStreams(null, null)")
    result match
      case Result.Err(error) => assert(error.contains("rejectSafe"))
      case Result.Ok(output) => fail(s"Trusted factory was callable from safe code: $output")
    assertEquals(ReplExec.decodeOutput(execute("puts(9)")), ScalaToolResult.Success(Vector(ujson.Num(9)), Vector.empty))
  }

  test("safe mode still rejects unchecked casts after trusted setup") {
    val result = ReplExec.runCode(builtins, Skills.Basic, "val value: Any = 1; value.asInstanceOf[String]")
    assert(result.isInstanceOf[Result.Err[?]])
  }

  test("a pure callback cannot forget the output capability it captures") {
    val result = ReplExec.runCode(builtins, Skills.Basic, "val log: String -> Unit = message => puts(message); log(\"test\")")
    assert(result.isInstanceOf[Result.Err[?]])
  }

  test("the filesystem comes from the manifest parameter, not an accessible global") {
    val filesystem = loaded("skills/filesystem")
    val result = ReplExec.runCode(builtins, filesystem, "fs.internal.impl().pwd()")
    result match
      case Result.Err(error) => assert(error.contains("rejectSafe"))
      case Result.Ok(output) => fail(s"Trusted filesystem factory was callable: $output")
  }

  test("explicit structured errors use the supplied output capability") {
    val output = execute("putsErr(\"failed deliberately\")")
    assertEquals(ReplExec.decodeOutput(output), ScalaToolResult.Success(Vector.empty, Vector(ujson.Str("failed deliberately"))))
  }

  test("stdout and stderr stay separate and preserve Unicode and per-channel order") {
    val output = execute("""puts("hello 🦫"); putsErr("warning λ"); puts(2); putsErr("again")""")
    assert(!output.failed)
    assertEquals(
      ReplExec.decodeOutput(output),
      ScalaToolResult.Success(Vector(ujson.Str("hello 🦫"), ujson.Num(2)), Vector(ujson.Str("warning λ"), ujson.Str("again")))
    )
    val encoded = ujson.read(ReplExec.decodeOutput(output).encodeAsJson)
    assertEquals(encoded("stdout"), ujson.Arr("hello 🦫", 2))
    assertEquals(encoded("stderr"), ujson.Arr("warning λ", "again"))
    assertEquals(execute("42"), CodeOutput("", ""))
  }

  test("tool results preserve both channels even when the snippet throws") {
    val output = execute("""puts(7); putsErr("warning"); val zero = 0; 1 / zero""")
    val result = ujson.read(ReplExec.decodeOutput(output).encodeAsJson)
    assert(output.failed)
    assertEquals(result("stdout"), ujson.Arr(7))
    assertEquals(result("stderr")(0), ujson.Str("warning"))
    assertEquals(result("stderr").arr.size, 2)
    assertEquals(result("executionError"), result("stderr")(1))
  }

  test("the developer command writes each channel to the matching process stream") {
    val stdout = new java.io.ByteArrayOutputStream()
    val stderr = new java.io.ByteArrayOutputStream()
    withSnippet("""puts("hello"); putsErr("warning")""") { path =>
      scala.Console.withOut(stdout) {
        scala.Console.withErr(stderr) {
          capybara.agent.run("skill-run", "--skill", "basic", "--file", path)
        }
      }
    }
    assertEquals(ujson.read(stdout.toString(java.nio.charset.StandardCharsets.UTF_8))("special.success"), ujson.Str("hello"))
    assertEquals(ujson.read(stderr.toString(java.nio.charset.StandardCharsets.UTF_8))("special.error"), ujson.Str("warning"))
  }

  test("output handles do not rely on Console initialization or redirect process streams") {
    val initialized = builtins.copy(predef = builtins.predef.map(_ + "; val priorOut = scala.Console.out; val priorErr = scala.Console.err"))
    ReplExec.ensureCompiled(initialized)
    val originalOut = System.out
    val originalErr = System.err
    val unrelatedOut = new java.io.ByteArrayOutputStream()
    val unrelatedErr = new java.io.ByteArrayOutputStream()
    val outStream = new java.io.PrintStream(unrelatedOut)
    val errStream = new java.io.PrintStream(unrelatedErr)
    try
      System.setOut(outStream)
      System.setErr(errStream)
      val result = ReplExec.runCode(initialized, Skills.Basic, """puts("captured out"); putsErr("captured err")""")
      assert(System.out eq outStream)
      assert(System.err eq errStream)
      result match
        case Result.Ok(output) =>
          assertEquals(ReplExec.decodeOutput(output), ScalaToolResult.Success(Vector(ujson.Str("captured out")), Vector(ujson.Str("captured err"))))
        case Result.Err(error) => fail(error)
      assertEquals(unrelatedOut.size(), 0)
      assertEquals(unrelatedErr.size(), 0)
    finally
      System.setOut(originalOut)
      System.setErr(originalErr)
      outStream.close()
      errStream.close()
  }

  test("skill IDE setup uses original files and requires no Mill project") {
    for directory <- Seq("skills/filesystem", "skills/secrets", "builtin-skills/capybara-builtins") do
      SkillIde.prepare(directory) match
        case Result.Ok(path) =>
          assert(os.list(path / ".bsp").exists(_.ext == "json"))
          val preview = os.read(path / ".agent-runtime" / "SkillPreview.scala")
          assert(preview.contains("using output: builtins.OutputStreams"))
          assert(preview.contains("builtins.outputStreams(__harnessStdout, __harnessStderr)"))
          assert(!os.exists(path / "package.mill.yaml"))
          // Validate exactly the source inputs recorded by setup-ide, including linked
          // builtin sources and the generated parameter/factory preview.
          val inputs = ujson.read(os.read(path / ".scala-build" / "ide-inputs.json"))("args").arr.map(_.str).toSeq
          val _ = os.call(
            Seq("scala", "compile") ++ inputs ++
              Seq("--scala", ReplExec.ScalaVersion, "--scalac-option=-preview"),
            cwd = path
          )
        case Result.Err(error) => fail(error)
  }
