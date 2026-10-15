package capybara.agent

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import steps.result.Result

class ToolExecutionRuntimeSuite extends munit.FunSuite:
  test("harness streams once and records success, compile failure and cancellation with unique IDs") {
    given ExecutionContext = ExecutionContext.global
    val builtins = Skills.loadFrom(os.pwd / "builtin-skills/capybara-builtins") match
      case Result.Ok(value) => value
      case Result.Err(error) => fail(error)
    val history = ToolExecutionHistory()
    val emitted = scala.collection.mutable.ArrayBuffer.empty[(Long, ToolOutputChannel, String)]
    val logger = new Logger:
      def print(message: String): Unit = ()
      def flush(): Unit = ()
      override def executionStarted(id: Long, universes: Seq[String], code: String): Unit =
        history.start(id, universes, code)
      override def executionRunning(id: Long): Unit = history.state(id, ToolExecutionState.Running)
      override def executionOutput(id: Long, channel: ToolOutputChannel, text: String): Unit =
        emitted += ((id, channel, text))
        history.append(id, channel, text)
      override def executionFinished(id: Long, state: ToolExecutionState): Unit = history.state(id, state)
    def run(code: String, token: CancellationToken = CancellationToken.Never) =
      Await.result(ReplExec.runCodeHarness(builtins, Skills.Basic, code, 1, logger, token), 30.seconds)
    val _ = run("puts(17); putsErr(18)")
    val first = history.snapshot.selected.get
    assertEquals(first.state, ToolExecutionState.Succeeded)
    assertEquals(emitted.map(_._3).toVector, Vector("17\n", "18\n"))
    val _ = run("missingCapability.operation()")
    assertEquals(history.snapshot.selected.get.state, ToolExecutionState.Failed)
    assert(history.snapshot.selected.get.output.nonEmpty)
    val cancelled = CancellationToken()
    cancelled.cancel()
    val _ = run("puts(99)", cancelled)
    assertEquals(history.snapshot.selected.get.state, ToolExecutionState.Cancelled)
    assertEquals(history.snapshot.selected.get.output, Vector.empty)
    assertEquals(history.snapshot.records.map(_.id).distinct.size, 3)
    val _ = history.select(ExecutionSelection.ById(first.id))
    assertEquals(history.snapshot.selected.get.output, first.output)
  }
