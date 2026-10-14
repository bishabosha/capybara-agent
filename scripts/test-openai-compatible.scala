//> using scala 3.10.0
//> using options -preview
//> using dep com.softwaremill.sttp.ai::openai:0.11.4
//> using dep com.lihaoyi::upickle-implicits-named-tuples:4.4.3
//> using dep com.lihaoyi::upickle-jsonschema:4.4.3
//> using dep ch.epfl.lamp::steps:0.2.1
//> using file ../src/OllamaClient.scala
//> using file ../src/OpenAICompatibleClient.scala
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
    println("OpenAI-compatible provider checks passed")
