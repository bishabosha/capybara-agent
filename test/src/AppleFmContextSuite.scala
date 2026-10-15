package capybara.agent

import OllamaClient.*
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import steps.result.Result

class AppleFmContextSuite extends munit.FunSuite:
  private given ExecutionContext = ExecutionContext.global
  private val definition = toolsDef[ScalaAgent.ScalaToolCall]("run_scala_code", "Execute Scala")
  private val normalResult = "{\"success\":true,\"stdout\":[221],\"stderr\":[]}"

  private def history(large: Boolean = false): ujson.Value =
    def user(text: String) = ujson.Obj("role" -> "user", "contents" -> ujson.Arr(ujson.Obj("text" -> text)))
    ujson.Obj("version" -> "1.1", "turn" -> 0, "transcript" -> ujson.Obj("entries" -> ujson.Arr(
      ujson.Obj("role" -> "instructions", "contents" -> ujson.Arr(ujson.Obj("text" -> "rules"))),
      user(if large then "x" * 14000 else "old question"),
      ujson.Obj("role" -> "response", "toolCalls" -> ujson.Arr(ujson.Obj("id" -> "old-call"))),
      ujson.Obj("role" -> "tool", "toolCallID" -> "old-call"),
      ujson.Obj("role" -> "response", "contents" -> ujson.Arr(ujson.Obj("text" -> "old answer"))),
      user("recent question"),
      ujson.Obj("role" -> "response", "toolCalls" -> ujson.Arr(ujson.Obj("id" -> "recent-call"))),
      ujson.Obj("role" -> "tool", "toolCallID" -> "recent-call"),
      ujson.Obj("role" -> "response", "contents" -> ujson.Arr(ujson.Obj("text" -> "recent answer")))
    )))

  private case class Run(result: Result[Unit, String], chunks: Vector[Chunk[ScalaAgent.ScalaToolCall]],
      notices: Vector[String], calls: Int):
    def state = chunks.collectFirst { case Chunk.ProviderState(_, state) => state }.get

  private def run(state: ujson.Value, prompt: String = "calculate", toolResult: String = normalResult): Run =
    val available = try new ProcessBuilder("python3", "--version").start().waitFor() == 0
      catch case _: java.io.IOException => false
    assume(available, "Optional Python bridge checks need python3")
    val messages = Vector(ChatMessage.system("rules"),
      ChatMessage(ujson.Obj("role" -> "assistant", "content" -> "previous answer"),
        providerState = Map("apple-fm" -> state)), ChatMessage.user(prompt))
    val chunks = new java.util.concurrent.ConcurrentLinkedQueue[Chunk[ScalaAgent.ScalaToolCall]]()
    val notices = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    val listener = new ChunkOutput.ChunkReader[Chunk[ScalaAgent.ScalaToolCall]]:
      def onChunk(chunk: Chunk[ScalaAgent.ScalaToolCall]): Unit =
        val _ = chunks.add(chunk)
        chunk match
          case Chunk.Tools(values) => values.foreach { value =>
            val _ = calls.incrementAndGet()
            value.resultSink.get(toolResult)
          }
          case _ => ()
      def onComplete(state: ChunkOutputState & ChunkFinalState, usage: Usage): Unit = ()
    val request = AppleFmClient.requestWithCommand(messages, definition.tools, definition.toolParsers,
      CancellationToken.Never, () => Seq("python3", "-u",
        (os.pwd / "test/resources/apple-fm-sdk-stub/run_bridge.py").toString,
        (os.pwd / "resources/apple_fm_bridge.py").toString),
      onNotice = text => { val _ = notices.add(text) })
    val result = Await.result(request.send(listener), 8.seconds)
    Run(result, chunks.iterator().asScala.toVector, notices.iterator().asScala.toVector, calls.get())

  test("context budget removes complete old turns and preserves recent call/result pairs") {
    val r = run(history(large = true))
    assertEquals(r.result, Result.Ok(()))
    assertEquals(r.calls, 1)
    val entries = r.state("transcript")("entries").arr
    assertEquals(entries.head("role").str, "instructions")
    val callIds = entries.flatMap(_.obj.get("toolCalls").toVector.flatMap(_.arr.map(_("id").str))).toVector
    val resultIds = entries.filter(_("role").str == "tool").map(_("toolCallID").str).toVector
    assertEquals(callIds, Vector("recent-call"))
    assertEquals(resultIds, callIds)
    assert(r.notices.exists(_.contains("shortened its model context")))
  }

  test("native overflow before execution renews context and executes the current tool only once") {
    val state = history()
    state("fail_before") = true
    val r = run(state)
    assertEquals(r.result, Result.Ok(()))
    assertEquals(r.calls, 1)
    assert(r.notices.exists(_.contains("no tools were replayed")))
  }

  test("native overflow after tool execution never retries the user's side effects") {
    val state = history()
    state("fail_after") = true
    val r = run(state)
    assertEquals(r.calls, 1)
    r.result match
      case Result.Err(error) => assert(error.contains("Already executed tools were not repeated"), error)
      case _ => fail("Expected a safe context failure after execution")
  }

  test("large tool output is shortened only in the SDK view and reports truncation") {
    val fullResult = ujson.write(ujson.Obj("success" -> true, "stdout" -> ujson.Arr("x" * 20000), "stderr" -> ujson.Arr()))
    val r = run(history(), toolResult = fullResult)
    assertEquals(r.result, Result.Ok(()))
    val view = r.state("observed_result")
    assert(view("output_truncated").bool)
    assert(ujson.write(view).length < fullResult.length)
    assert(r.notices.exists(_.contains("shortened tool result")))
  }

  test("a single oversized prompt gets an actionable error without executing tools") {
    val r = run(history(), prompt = "x" * 20000)
    assertEquals(r.calls, 0)
    r.result match
      case Result.Err(error) => assert(error.contains("Shorten this request"), error)
      case _ => fail("Expected an oversized-input error")
  }
