//> using scala 3.10.0
//> using options -preview
//> using dep com.softwaremill.sttp.ai::openai:0.11.4
//> using dep com.lihaoyi::upickle-implicits-named-tuples:4.4.3
//> using dep com.lihaoyi::upickle-jsonschema:4.4.3
//> using dep ch.epfl.lamp::steps:0.2.1
//> using file ../src/OllamaClient.scala
//> using file ../src/OpenAICompatibleClient.scala
//> using file ../src/OpenAIResponsesClient.scala
//> using file ../src/CancellationToken.scala

package capybara.agent

import OllamaClient.*
import sttp.ai.openai.requests.completions.chat.message.{Message, Tool}
import sttp.ai.openai.requests.completions.chat.ToolCall as OpenAIToolCall
import upickle.default.*
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

object OpenAICompatibleClientTest:
  case class Arguments(value: String) derives ReadWriter

  private final class Capture:
    val output = new ChunkOutput[Chunk[Arguments]]
    val chunks = ArrayBuffer.empty[Chunk[Arguments]]
    var usage = Usage.Empty
    var completed = false
    output.registerListener(new ChunkOutput.ChunkReader[Chunk[Arguments]]:
      def onChunk(chunk: Chunk[Arguments]): Unit = { chunks += chunk; () }
      def onComplete(state: ChunkOutputState & ChunkFinalState, value: Usage): Unit =
        assert(state == ChunkOutputState.Done)
        usage = value
        completed = true
    )
    val decoder = new OpenAICompatibleClient.StreamDecoder(
      Map("run" -> summon[ReadWriter[Arguments]]), output)

  private def event(delta: ujson.Value, finish: ujson.Value = ujson.Null): String =
    ujson.write(ujson.Obj("choices" -> ujson.Arr(ujson.Obj(
      "index" -> 0, "delta" -> delta, "finish_reason" -> finish))))

  private def fragment(index: Int, id: String, name: String, arguments: String): ujson.Value =
    ujson.Obj("index" -> index, "id" -> id,
      "function" -> ujson.Obj("name" -> name, "arguments" -> arguments))

  @main def testOpenAICompatibleClient(): Unit =
    val capture = new Capture
    capture.decoder.accept(event(ujson.Obj("role" -> "assistant", "content" -> ujson.Null)))
    capture.decoder.accept(event(ujson.Obj("reasoning_content" -> "considering", "content" -> "hello")))
    capture.decoder.accept(event(ujson.Obj("tool_calls" -> ujson.Arr(
      fragment(1, "call_b", "run", "{\"value\":\"b"),
      fragment(0, "call_a", "run", "{\"value\":\"a")))))
    capture.decoder.accept(event(ujson.Obj("tool_calls" -> ujson.Arr(
      fragment(0, "", "", "\"}"), fragment(1, "", "", "\"}"))), ujson.Str("tool_calls")))
    assert(!capture.completed)
    assert(!capture.chunks.exists { case Chunk.Tools(_) => true; case _ => false })
    capture.decoder.accept("""{"choices":[],"usage":{"prompt_tokens":42,"completion_tokens":7}}""")
    capture.decoder.accept("[DONE]")
    assert(capture.completed)
    assert(capture.usage == Usage(Some(42), Some(7)))
    assert(capture.chunks.contains(Chunk.Content("hello")))
    assert(capture.chunks.contains(Chunk.Thinking("considering")))
    val calls = capture.chunks.collect { case Chunk.Tools(calls) => calls }.flatten.toVector
    assert(calls == Vector(
      ToolCall("run", Arguments("a"), Some("call_a")),
      ToolCall("run", Arguments("b"), Some("call_b"))))

    val assistant = ChatMessage.assistant("private reasoning", "hello", calls)
    val converted = OpenAICompatibleClient.toMessage(assistant).asInstanceOf[Message.Assistant]
    assert(converted.content == "hello")
    val first = converted.toolCalls.head.asInstanceOf[OpenAIToolCall.FunctionToolCall]
    assert(first.id.contains("call_a"))
    assert(read[Arguments](first.function.arguments) == Arguments("a"))
    val result = ChatMessage.tool("run", "result", Some("call_a"))
    assert(OpenAICompatibleClient.toMessage(result) == Message.Tool("result", "call_a"))
    assert(OpenAICompatibleClient.toMessage(ChatMessage.system("system")) == Message.System("system"))
    val definition: ToolDefinition = (`type` = "function", function = (
      name = "run", parameters = ujson.Obj("type" -> "object"), description = "Run a tool"))
    val tool = OpenAICompatibleClient.toTool(definition).asInstanceOf[Tool.Function]
    assert(tool.name == "run" && tool.description.contains("Run a tool"))
    assert(tool.parameters.flatMap(_.get("type")).flatMap(_.asString).contains("object"))
    // The default Ollama format remains valid without OpenAI IDs.
    val ollama = ChatMessage.assistant("", "", Vector(ToolCall("run", Arguments("local"))))
    assert(!ollama.json("tool_calls")(0).obj.contains("id"))

    val text = new Capture
    text.decoder.accept(event(ujson.Obj("content" -> "done"), ujson.Str("stop")))
    text.decoder.accept("[DONE]")
    assert(text.completed && text.usage == Usage.Empty)

    val truncated = new Capture
    assert(Try(truncated.decoder.accept(event(ujson.Obj(), ujson.Str("length")))).isFailure)
    assert(Try(new Capture().decoder.accept("[DONE]")).isFailure)
    assert(Try(new Capture().decoder.accept("""{"error":{"message":"bad request"}}""")).isFailure)
    val invalidTool = new Capture
    invalidTool.decoder.accept(event(ujson.Obj("tool_calls" -> ujson.Arr(
      fragment(0, "call_bad", "unknown", "{}"))), ujson.Str("tool_calls")))
    assert(Try(invalidTool.decoder.accept("[DONE]")).isFailure)
    assert(!invalidTool.completed && invalidTool.chunks.isEmpty)
    OpenAIResponsesClientTest.run()
    println("OpenAI-compatible Chat Completions and Responses provider checks passed")

object OpenAIResponsesClientTest:
  import OpenAICompatibleClientTest.Arguments
  import sttp.ai.openai.requests.responses.ResponsesRequestBody.Input

  private final class Capture:
    val chunks = ArrayBuffer.empty[Chunk[Arguments]]
    val output = new ChunkOutput[Chunk[Arguments]]
    var usage = Usage.Empty
    var completed = false
    output.registerListener(new ChunkOutput.ChunkReader[Chunk[Arguments]]:
      def onChunk(chunk: Chunk[Arguments]): Unit = { chunks += chunk; () }
      def onComplete(state: ChunkOutputState & ChunkFinalState, value: Usage): Unit =
        assert(state == ChunkOutputState.Done)
        completed = true
        usage = value
    )
    val decoder = new OpenAIResponsesClient.StreamDecoder(
      Map("run" -> summon[ReadWriter[Arguments]]), output)

  private def call(id: String, value: String): ujson.Value =
    ujson.Obj("type" -> "function_call", "id" -> s"fc_$id", "call_id" -> id,
      "name" -> "run", "arguments" -> write(Arguments(value)), "status" -> "completed")

  private def message(value: String): ujson.Value =
    ujson.Obj("type" -> "message", "id" -> "msg_1", "role" -> "assistant",
      "status" -> "completed", "content" -> ujson.Arr(
        ujson.Obj("type" -> "output_text", "text" -> value, "annotations" -> ujson.Arr())))

  private def complete(items: Vector[ujson.Value], usage: ujson.Value = ujson.Null): String =
    ujson.write(ujson.Obj("type" -> "response.completed", "response" -> ujson.Obj(
      "status" -> "completed", "output" -> ujson.Arr.from(items), "usage" -> usage)))

  def run(): Unit =
    val reasoning = ujson.Obj("type" -> "reasoning", "id" -> "rs_1",
      "encrypted_content" -> "opaque reasoning", "summary" -> ujson.Arr(
        ujson.Obj("type" -> "summary_text", "text" -> "considering")))
    val items = Vector(reasoning, message("hello"), call("call_a", "a"), call("call_b", "b"))
    val capture = new Capture
    capture.decoder.accept("""{"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":0,"delta":"considering"}""")
    capture.decoder.accept("""{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"hel"}""")
    capture.decoder.accept("""{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"lo"}""")
    // Function argument fragments may interleave; execute only canonical completed calls.
    capture.decoder.accept("""{"type":"response.function_call_arguments.delta","output_index":3,"delta":"{\"value\":"}""")
    capture.decoder.accept("""{"type":"response.function_call_arguments.delta","output_index":2,"delta":"{\"value\":"}""")
    capture.decoder.accept(ujson.write(ujson.Obj("type" -> "response.output_item.done",
      "output_index" -> 2, "item" -> items(2))))
    assert(!capture.chunks.exists { case Chunk.Tools(_) => true; case _ => false })
    capture.decoder.accept(complete(items, ujson.Obj("input_tokens" -> 12, "output_tokens" -> 4)))
    assert(capture.completed && capture.usage == Usage(Some(12), Some(4)))
    assert(capture.chunks.collect { case Chunk.Content(value) => value }.mkString == "hello")
    assert(capture.chunks.collect { case Chunk.Thinking(value) => value }.mkString == "considering")
    assert(capture.chunks.collect { case Chunk.Tools(calls) => calls }.flatten.toVector == Vector(
      ToolCall("run", Arguments("a"), Some("call_a")),
      ToolCall("run", Arguments("b"), Some("call_b"))))
    assert(capture.chunks.contains(Chunk.ResponseItems(items)))

    val history = ChatMessage.assistant("", "hello", Vector.empty[ToolCall[Arguments]])
      .copy(responseItems = Some(items))
    val replay = OpenAIResponsesClient.toInput(history)
    assert(replay.size == 4)
    val preserved = replay.head.asInstanceOf[Input.Reasoning]
    assert(preserved.encryptedContent.contains("opaque reasoning"))
    assert(preserved.summary.head.text == "considering")
    assert(replay(2).asInstanceOf[Input.FunctionCall].callId == "call_a")
    val toolResult = ChatMessage.tool("run", "result", Some("call_a"))
    assert(OpenAIResponsesClient.toInput(toolResult) == List(Input.FunctionCallOutput("call_a", "result")))
    val assistant = ChatMessage.assistant("", "", Vector(ToolCall("run", Arguments("a"), Some("call_a"))))
    assert(OpenAIResponsesClient.toInput(assistant) == List(Input.FunctionCall(
      write(Arguments("a")), "call_a", "run")))

    val definition: ToolDefinition = (`type` = "function", function = (
      name = "run", parameters = ujson.Obj("type" -> "object"), description = "Run a tool"))
    val body = OpenAIResponsesClient.requestBody("muse-spark-1.3-contributor-free",
      Seq(ChatMessage.system("system"), ChatMessage.user("query"), history, toolResult), Vector(definition))
    assert(body.model.get.value == "muse-spark-1.3-contributor-free")
    assert(body.store.contains(false) && body.previousResponseId.isEmpty)
    assert(body.input.get.toOption.get.size == 7)
    val tool = body.tools.get.head.asInstanceOf[sttp.ai.openai.requests.responses.Tool.Function]
    assert(tool.name == "run" && !tool.strict)
    assert(tool.parameters("type").asString.contains("object"))
    val request = new sttp.ai.openai.OpenAI("test-key",
      sttp.model.Uri.unsafeParse("https://opencode.ai/inference/v1"))
      .createModelResponseAsInputStream(body)
    assert(request.uri.toString == "https://opencode.ai/inference/v1/responses")
    assert(request.header("Authorization").contains("Bearer test-key"))
    val wire = ujson.read(request.body.asInstanceOf[sttp.client4.StringBody].s)
    assert(wire("model").str == "muse-spark-1.3-contributor-free")
    assert(wire("stream").bool && !wire("store").bool)
    assert(wire("tools")(0)("type").str == "function")
    assert(wire("tools")(0)("name").str == "run")
    assert(!wire("tools")(0).obj.contains("function"))
    assert(wire("input")(2)("encrypted_content").str == "opaque reasoning")
    assert(wire("input")(6)("type").str == "function_call_output")
    assert(wire("input")(6)("call_id").str == "call_a")

    val fallback = new Capture
    fallback.decoder.accept(complete(Vector(message("final text"))))
    assert(fallback.completed && fallback.usage == Usage.Empty)
    assert(fallback.chunks.contains(Chunk.Content("final text")))
    val invalid = new Capture
    val bad = call("bad", "value")
    bad("arguments") = ujson.Str("{")
    assert(Try(invalid.decoder.accept(complete(Vector(items(2), bad)))).isFailure)
    assert(!invalid.completed && invalid.chunks.isEmpty)
    val incomplete = new Capture
    val incompleteResponse = ujson.read(complete(items))
    incompleteResponse("response")("status") = ujson.Str("incomplete")
    assert(Try(incomplete.decoder.accept(ujson.write(incompleteResponse))).isFailure)
    assert(!incomplete.completed && incomplete.chunks.isEmpty)
    assert(Try(new Capture().decoder.accept("[DONE]")).isFailure)
    for kind <- List("response.failed", "response.incomplete", "response.cancelled", "error") do
      val failed = new Capture
      assert(Try(failed.decoder.accept(ujson.write(ujson.Obj("type" -> kind)))).isFailure)
      assert(!failed.completed && failed.chunks.isEmpty)
