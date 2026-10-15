package capybara.agent

import OllamaClient.*
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import steps.result.Result
import upickle.default.*

class AppleFmClientSuite extends munit.FunSuite:
  private given ExecutionContext = ExecutionContext.global
  private val definition = toolsDef[ScalaAgent.ScalaToolCall]("run_scala_code", "Execute Scala with puts(value)")
  private val messages = Vector(ChatMessage.system("Use the tool"), ChatMessage.user("calculate"))
  private val result = "{\"success\":true,\"stdout\":[221],\"stderr\":[]}"
  private val nativeState = ujson.Obj("version" -> "test", "entries" -> ujson.Arr("completed"))

  private class Capture:
    val output = new ChunkOutput[Chunk[ScalaAgent.ScalaToolCall]]()
    val chunks = scala.collection.mutable.ArrayBuffer.empty[Chunk[ScalaAgent.ScalaToolCall]]
    var completed = false
    val listener = new ChunkOutput.ChunkReader[Chunk[ScalaAgent.ScalaToolCall]]:
      def onChunk(chunk: Chunk[ScalaAgent.ScalaToolCall]): Unit = { val _ = chunks += chunk }
      def onComplete(state: ChunkOutputState & ChunkFinalState, usage: Usage): Unit = completed = state.isDone
    val _ = output.registerListener(listener)

  private def call(id: String = "one", name: String = "run_scala_code", args: ujson.Value =
      ujson.Obj("universe" -> "basic", "scala_code" -> "puts(13 * 17)")): ujson.Value =
    ujson.Obj("type" -> "tool_call", "id" -> id, "name" -> name, "arguments" -> args)

  test("construction and pre-cancelled requests never launch or inspect Python") {
    var launched = false
    val token = CancellationToken()
    val request = AppleFmClient.requestWithCommand(messages, definition.tools, definition.toolParsers, token,
      () => { launched = true; throw IllegalStateException("must not run") })
    assert(!launched)
    assert(request.completesToolLoop)
    token.cancel()
    assertEquals(Await.result(request.send(), 2.seconds), Result.Err("cancelled"))
    assert(!launched)
    // Even unavailable interpreters/models are not validated until this provider is sent.
    val _ = AppleFmClient.request(messages, definition.tools, definition.toolParsers,
      CancellationToken.Never, "missing-python", "not-a-model")
  }

  test("typed SDK callbacks return results once and commit native state only on completion") {
    val c = Capture()
    val replies = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    val decoder = new AppleFmClient.Decoder(definition.toolParsers, c.output, (id, text) => { val _ = replies += ((id, text)) })
    decoder.accept(call())
    val tool = c.chunks.collectFirst { case Chunk.Tools(calls) => calls.head }.get
    assertEquals(tool.arguments.scala_code, "puts(13 * 17)")
    assertEquals(tool.id, Some("one"))
    assert(!c.completed)
    tool.resultSink.get(result)
    tool.resultSink.get(result)
    assertEquals(replies.toVector, Vector(("one", result)))
    decoder.accept(ujson.Obj("type" -> "content", "text" -> "221"))
    decoder.accept(ujson.Obj("type" -> "state", "transcript" -> nativeState))
    assert(!c.chunks.exists { case Chunk.ProviderState(_, _) => true; case _ => false })
    decoder.accept(ujson.Obj("type" -> "done"))
    assert(c.completed)
    assert(c.chunks.contains(Chunk.ProviderState("apple-fm", nativeState)))
  }

  test("malformed, unknown, duplicate, and unresolved calls fail closed") {
    def decoder() = new AppleFmClient.Decoder(definition.toolParsers, Capture().output, (_, _) => ())
    val _ = intercept[IllegalArgumentException](decoder().accept(call(name = "unknown")))
    val _ = intercept[IllegalArgumentException](decoder().accept(call(args = ujson.Obj("universe" -> "basic"))))
    val duplicate = decoder()
    duplicate.accept(call())
    val _ = intercept[IllegalArgumentException](duplicate.accept(call()))
    val unresolved = decoder()
    unresolved.accept(call())
    val _ = intercept[IllegalArgumentException](unresolved.accept(ujson.Obj("type" -> "done")))
    val _ = intercept[IllegalArgumentException](decoder().accept(ujson.Obj("type" -> "done")))
  }

  test("native transcript survives host history and is replayed without repeating tool results") {
    val chunks = Vector[ScalaAgent.ScalaResolvedChunk](
      ResolvedChunk.Tools(Vector((ToolCall("run_scala_code", ScalaAgent.ScalaToolCall(scala_code = "puts(1)"), Some("one")),
        ReplExec.ScalaToolResult.NoOutput))),
      ResolvedChunk.Content("done"), ResolvedChunk.ProviderState("apple-fm", nativeState))
    val history = ScalaAgent.appendCollectedChunks(messages, chunks) :+ ChatMessage.user("follow up")
    val payload = AppleFmClient.payload(history, definition.tools)
    assertEquals(payload("transcript"), nativeState)
    assertEquals(payload("prompt").str, "follow up")
    assertEquals(AppleFmClient.payload(messages, definition.tools)("transcript"), ujson.Null)
    val _ = intercept[IllegalArgumentException](AppleFmClient.payload(
      Vector(ChatMessage.user("old"), ChatMessage(ujson.Obj("role" -> "assistant", "content" -> "old answer")),
        ChatMessage.user("new")), definition.tools))
  }

  private def worker(mode: String, token: CancellationToken) =
    AppleFmClient.requestWithCommand(messages, definition.tools, definition.toolParsers, token,
      () => Seq("python3", (os.pwd / "test/resources/apple-fm-fake.py").toString, mode))

  private def requirePython(): Unit =
    val available = try new ProcessBuilder("python3", "--version").start().waitFor() == 0
      catch case _: java.io.IOException => false
    assume(available, "Optional Python transport tests require python3")

  test("bidirectional subprocess transport completes one native tool loop without the SDK") {
    requirePython()
    val c = Capture()
    val listener = new ChunkOutput.ChunkReader[Chunk[ScalaAgent.ScalaToolCall]]:
      def onChunk(chunk: Chunk[ScalaAgent.ScalaToolCall]): Unit =
        c.listener.onChunk(chunk)
        chunk match
          case Chunk.Tools(calls) => calls.foreach(_.resultSink.get(result))
          case _ => ()
      def onComplete(state: ChunkOutputState & ChunkFinalState, usage: Usage): Unit = c.listener.onComplete(state, usage)
    assertEquals(Await.result(worker("tool", CancellationToken.Never).send(listener), 5.seconds), Result.Ok(()))
    assert(c.completed)
    assert(c.chunks.contains(Chunk.Content("221")))
  }

  test("bundled bridge handles blocking SDK streams, cross-loop callbacks and native transcript replay") {
    requirePython()
    def send(history: Vector[ChatMessage]): Vector[Chunk[ScalaAgent.ScalaToolCall]] =
      val c = Capture()
      val listener = new ChunkOutput.ChunkReader[Chunk[ScalaAgent.ScalaToolCall]]:
        def onChunk(chunk: Chunk[ScalaAgent.ScalaToolCall]): Unit =
          c.listener.onChunk(chunk)
          chunk match
            case Chunk.Tools(calls) => calls.foreach(_.resultSink.get(result))
            case _ => ()
        def onComplete(state: ChunkOutputState & ChunkFinalState, usage: Usage): Unit = c.listener.onComplete(state, usage)
      val request = AppleFmClient.requestWithCommand(history, definition.tools, definition.toolParsers,
        CancellationToken.Never, () => Seq("python3", "-u",
          (os.pwd / "test/resources/apple-fm-sdk-stub/run_bridge.py").toString,
          (os.pwd / "resources/apple_fm_bridge.py").toString))
      assertEquals(Await.result(request.send(listener), 6.seconds), Result.Ok(()))
      assertEquals(c.chunks.collect { case Chunk.Content(text) => text }.mkString, "221")
      c.chunks.toVector
    val first = send(messages)
    val state = first.collectFirst { case Chunk.ProviderState(_, state) => state }.get
    assertEquals(state("turn").num, 1.0)
    val history = messages ++ Vector(
      ChatMessage(ujson.Obj("role" -> "assistant", "content" -> "221"), providerState = Map("apple-fm" -> state)),
      ChatMessage.user("follow up"))
    val second = send(history)
    assertEquals(second.collectFirst { case Chunk.ProviderState(_, state) => state("turn").num }, Some(2.0))
  }

  test("cancellation terminates the selected Python worker promptly") {
    requirePython()
    val token = CancellationToken()
    val started = new java.util.concurrent.CountDownLatch(1)
    var pid = 0L
    val listener = new ChunkOutput.ChunkReader[Chunk[ScalaAgent.ScalaToolCall]]:
      def onChunk(chunk: Chunk[ScalaAgent.ScalaToolCall]): Unit = chunk match
        case Chunk.Content(text) => pid = text.toLong; started.countDown()
        case _ => ()
      def onComplete(state: ChunkOutputState & ChunkFinalState, usage: Usage): Unit = ()
    val running = worker("wait", token).send(listener)
    assert(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
    token.cancel()
    assertEquals(Await.result(running, 3.seconds), Result.Err("cancelled"))
    assert(!java.lang.ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false))
  }

  test("worker load failures are reported without inheriting stderr into the terminal") {
    requirePython()
    val failure = Await.result(worker("crash", CancellationToken.Never).send(), 5.seconds)
    failure match
      case Result.Err(error) => assert(error.contains("FAKE SDK LOAD FAILED"), error)
      case other => fail(other.toString)
  }
