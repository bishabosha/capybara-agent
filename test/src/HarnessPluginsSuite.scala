package capybara.agent

class HarnessPluginsSuite extends munit.FunSuite:
  private val context = PluginContext(os.pwd, Vector.empty)
  private def extension(name: String, command: String = "hello"): HarnessPlugin = new HarnessPlugin:
    val id = name
    val description = "test extension"
    override val commands = Seq(PluginCommand(command, "test command", (args, _) => PluginAction.Message(args)))

  test("CLI exposes --plugin-dir and rejects plugins for one-shot queries before contacting a provider") {
    val parser = mainargs.ParserForMethods(capybara.agent)
    assert(parser.helpText().contains("--plugin-dir"))
    val result = parser.runEither(Seq("--query", "hello", "--plugin-dir", "examples/plugins"))
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("interactive mode only"))
  }

  test("dispatch preserves arguments and reports unknown commands") {
    val runtime = HarnessPlugins(Vector(extension("hello")))
    assertEquals(runtime.dispatch("/hello   a b /c", context), Some(Right(PluginAction.Message("a b /c"))))
    assertEquals(runtime.dispatch("/missing", context), None)
    assertEquals(runtime.commandNames, Vector("/hello"))
    assert(runtime.help.contains("[hello]"))
  }

  test("duplicate ids, command collisions, built-in overrides and invalid names are rejected") {
    val _ = intercept[IllegalArgumentException](HarnessPlugins(Vector(extension("same", "one"), extension("same", "two"))))
    val _ = intercept[IllegalArgumentException](HarnessPlugins(Vector(extension("one"), extension("two"))))
    val _ = intercept[IllegalArgumentException](HarnessPlugins(Vector(extension("one", "reload"))))
    val _ = intercept[IllegalArgumentException](HarnessPlugins(Vector(extension("one", "/hello"))))
    val _ = intercept[IllegalArgumentException](HarnessPlugins(Vector(extension("Bad Name"))))
  }

  test("transforms compose in load order; command and transform exceptions are contained") {
    def transform(name: String, suffix: String): HarnessPlugin = new HarnessPlugin:
      val id = name
      val description = name
      override def transformPrompt(prompt: String, context: PluginContext): String = prompt + suffix
    val runtime = HarnessPlugins(Vector(transform("one", "1"), transform("two", "2")))
    assertEquals(runtime.transform("query", context), Right("query12"))
    val broken = new HarnessPlugin:
      val id = "broken"
      val description = "broken"
      override val commands = Seq(PluginCommand("boom", "fail", (_, _) => throw IllegalStateException("command failed")))
      override def transformPrompt(prompt: String, context: PluginContext): String = throw IllegalStateException("transform failed")
    val failing = HarnessPlugins(Vector(broken))
    assertEquals(failing.dispatch("/boom", context), Some(Left("plugin broken: command failed")))
    assertEquals(failing.transform("query", context), Left("plugin broken: transform failed"))
  }

  test("event observer failure does not prevent later observers or cleanup") {
    var observed = false
    var cleaned = false
    val bad = new HarnessPlugin:
      val id = "bad"
      val description = "bad"
      override def onEvent(event: PluginEvent, context: PluginContext): Unit = throw IllegalStateException("observer")
      override def close(): Unit = throw IllegalStateException("cleanup")
    val good = new HarnessPlugin:
      val id = "good"
      val description = "good"
      override def onEvent(event: PluginEvent, context: PluginContext): Unit = observed = true
      override def close(): Unit = cleaned = true
    val runtime = HarnessPlugins(Vector(good, bad))
    assertEquals(runtime.emit(PluginEvent.SessionStarted, context).size, 1)
    assert(observed)
    runtime.close()
    assert(cleaned)
  }

  test("built-in workflows return prompts and usage messages without calling a model") {
    val runtime = HarnessPlugins(Vector(WorkflowPlugin()))
    assertEquals(runtime.dispatch("/plan", context), Some(Right(PluginAction.Message("usage: /plan <goal>"))))
    assertEquals(runtime.dispatch("/handoff", context), Some(Right(PluginAction.Message("No conversation to hand off yet."))))
    val review = runtime.dispatch("/review src", context).get.toOption.get
    review match
      case PluginAction.Submit(prompt) =>
        assert(prompt.contains("Review src"))
        assert(prompt.contains("Do not edit"))
      case other => fail(s"Expected prompt, got $other")
  }

  test("source plugin loads against host API and reloads with fresh state") {
    val root = os.pwd / "examples/plugins"
    def load(): HarnessPlugins = HarnessPlugins.load(Some(root)) match
      case Right(runtime) => runtime
      case Left(error) => fail(error)
    val first = load()
    try
      assert(first.commandNames.contains("/focus"))
      val _ = first.dispatch("/focus Keep changes small", context)
      assert(first.transform("implement it", context).toOption.get.contains("Keep changes small"))
      val _ = first.emit(PluginEvent.TurnCompleted, context)
      assertEquals(first.dispatch("/flow-stats", context), Some(Right(PluginAction.Message("Completed turns: 1"))))
      val second = load()
      try assertEquals(second.dispatch("/focus", context), Some(Right(PluginAction.Message("No focus set."))))
      finally second.close()
    finally first.close()
  }

  test("invalid source, missing configuration and conflicting source commands fail loading") {
    val root = os.temp.dir(prefix = "capybara-plugin-invalid-")
    try
      assert(HarnessPlugins.load(Some(root / "missing")).isLeft)
      os.makeDir.all(root / "bad")
      assert(HarnessPlugins.load(Some(root)).isLeft)
      os.write(root / "bad/plugin.properties", "class=Bad")
      os.write(root / "bad/Bad.scala", "this is invalid Scala!")
      val failed = HarnessPlugins.load(Some(root))
      assert(failed.left.toOption.get.contains("Could not compile plugin"))
      os.write.over(root / "bad/Bad.scala", """import capybara.agent.*
        |class Bad extends HarnessPlugin:
        |  val id = "bad"
        |  val description = "bad"
        |  override val commands = Seq(PluginCommand("reload", "override", (_, _) => PluginAction.Message("bad")))
        |""".stripMargin)
      assert(HarnessPlugins.load(Some(root)).left.toOption.get.contains("conflicts with a built-in"))
    finally os.remove.all(root)
  }
