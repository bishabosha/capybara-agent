package capybara.agent

import scala.concurrent.{ExecutionContext, Promise}
import steps.result.Result

class InteractiveSessionSuite extends munit.FunSuite:
  private given ExecutionContext = ExecutionContext.parasitic

  private class Fixture:
    val events = scala.collection.mutable.ArrayBuffer.empty[PluginEvent]
    val requests = scala.collection.mutable.ArrayBuffer.empty[(String, Vector[OllamaClient.ChatMessage], CancellationToken)]
    val pending = scala.collection.mutable.ArrayBuffer.empty[Promise[Result[Vector[OllamaClient.ChatMessage], String]]]
    val output = new StringBuilder
    var closes = 0
    var themeName = "test-theme"
    var appliedUi = TerminalUi()
    val uiContexts = scala.collection.mutable.ArrayBuffer.empty[TerminalUiContext]
    var reload: Either[String, HarnessPlugins] = Left("replacement failed")
    given Logger = new Logger:
      def print(message: String): Unit = { val _ = output.append(message) }
      def flush(): Unit = ()
      override def terminalUi: TerminalUi = appliedUi
      override def setUi(ui: TerminalUi): Unit = appliedUi = ui
      override def setUiContext(context: TerminalUiContext): Unit = { val _ = uiContexts += context }
    val observer = new HarnessPlugin:
      val id = "observer"
      val description = "test"
      override def configureUi(current: TerminalUi): TerminalUi = current.copy(name = themeName)
      override val commands = Seq(PluginCommand("appearance", "test appearance", (args, _) =>
        themeName = args
        PluginAction.Message("appearance changed")))
      override def transformPrompt(prompt: String, context: PluginContext): String = "focused: " + prompt
      override def onEvent(event: PluginEvent, context: PluginContext): Unit = { val _ = events += event }
      override def close(): Unit = closes += 1
    val session = InteractiveSession(
      HarnessPlugins(Vector(WorkflowPlugin(), observer)),
      () => reload,
      () => "usage stats",
      () => "system prompt",
      (prompt, history, _, token, _) =>
        requests += ((prompt, history, token))
        val promise = Promise[Result[Vector[OllamaClient.ChatMessage], String]]()
        pending += promise
        promise.future,
      () => false
    )
    def send(line: String): Unit = { val _ = session.accept(line) }
    def finish(index: Int, history: Vector[OllamaClient.ChatMessage]): Unit =
      val _ = pending(index).success(Result.Ok(history))
    def open(): Unit = session.open()
    def shutdown(): Unit = session.close()

  test("commands remain local, unknown commands do not submit and blank input is ignored") {
    val f = new Fixture
    Seq("/help", "/plugins", "/usage", "/sys-prompt", "/think off", "/think nonsense", "/no-such-command", "  ").foreach(f.send)
    assertEquals(f.requests.size, 0)
    assert(f.output.toString.contains("Unknown command"))
    assert(f.output.toString.contains("usage stats"))
    assert(f.session.commandNames.contains("/review"))
    f.shutdown()
  }

  test("plugin workflows transform prompts and completed history feeds the next turn") {
    val f = new Fixture
    f.send("/plan add plugins")
    assert(f.requests.head._1.startsWith("focused: Plan"))
    val history = Vector(OllamaClient.ChatMessage.user("first turn"))
    f.finish(0, history)
    f.send("follow up")
    assertEquals(f.requests(1)._2, history)
    assert(f.events.contains(PluginEvent.TurnCompleted))
    f.shutdown()
  }

  test("replacement, /x and /clear invalidate stale asynchronous results") {
    val f = new Fixture
    f.send("old")
    f.send("new")
    assert(f.requests.head._3.isCancelled)
    f.finish(0, Vector(OllamaClient.ChatMessage.user("stale")))
    f.send("/x")
    f.finish(1, Vector(OllamaClient.ChatMessage.user("also stale")))
    assert(!f.events.contains(PluginEvent.TurnCompleted))
    f.send("third")
    f.finish(2, Vector(OllamaClient.ChatMessage.user("third")))
    f.send("/clear")
    f.send("fresh")
    assertEquals(f.requests.last._2, Vector.empty)
    f.shutdown()
  }

  test("failed reload preserves runtime; successful reload closes old instance; busy reload is refused") {
    val f = new Fixture
    f.send("/reload")
    assertEquals(f.closes, 0)
    assert(f.output.toString.contains("replacement failed"))
    f.send("active")
    f.send("/reload")
    assert(f.output.toString.contains("Interrupt the active turn"))
    f.send("/x")
    f.reload = Right(HarnessPlugins(Vector(WorkflowPlugin())))
    f.send("/reload")
    assertEquals(f.closes, 1)
    f.send("after reload")
    assertEquals(f.requests.last._1, "after reload")
    f.shutdown()
  }

  test("failed futures report errors and shutdown cancels without publishing late completion") {
    val f = new Fixture
    f.send("fail")
    val _ = f.pending.head.failure(IllegalStateException("provider failed"))
    assert(f.output.toString.contains("provider failed"))
    assert(f.events.exists { case PluginEvent.TurnFailed(_) => true; case _ => false })
    f.send("running")
    f.shutdown()
    f.shutdown()
    assert(f.requests.last._3.isCancelled)
    assertEquals(f.closes, 1)
    f.finish(1, Vector.empty)
    assert(!f.events.contains(PluginEvent.TurnCompleted))
    assertEquals(f.events.last, PluginEvent.SessionClosed)
  }

  test("UI installs on open, refreshes after commands, follows turn state, and reload resets it") {
    val f = new Fixture
    f.open()
    assertEquals(f.appliedUi.name, "test-theme")
    assert(f.session.commandNames.contains("/appearance"))
    assertEquals(f.uiContexts.last.state, "idle")
    f.send("/appearance another-theme")
    assertEquals(f.appliedUi.name, "another-theme")
    f.send("/appearance ")
    assertEquals(f.appliedUi.name, "another-theme")
    f.send("run")
    assertEquals(f.uiContexts.last.state, "running")
    f.finish(0, Vector(OllamaClient.ChatMessage.user("run")))
    assertEquals(f.uiContexts.last.state, "idle")
    assertEquals(f.uiContexts.last.turns, 1)
    f.send("/think on")
    assertEquals(f.uiContexts.last.thinking, "on")
    f.send("/clear")
    assertEquals(f.uiContexts.last.turns, 0)
    f.reload = Right(HarnessPlugins(Vector(WorkflowPlugin())))
    f.send("/reload")
    assertEquals(f.appliedUi.name, "default")
    assert(!f.session.commandNames.contains("/appearance"))
    f.shutdown()
  }
