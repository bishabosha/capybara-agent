package capybara.agent

import capybara.agent.OllamaClient.*
import sttp.ai.openai.OpenAI
import sttp.ai.openai.requests.completions.chat.ChatRequestBody.*
import sttp.ai.openai.requests.completions.chat.{FunctionCall, ToolCall as OpenAIToolCall}
import sttp.ai.openai.requests.completions.chat.message.{Content, Message, Tool}
import sttp.client4.*
import sttp.model.Uri
import steps.result.Result
import upickle.default.*

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.util.Using
import scala.util.control.NonFatal

object OpenAICompatibleClient:
  private[agent] def toMessage(message: ChatMessage): Message =
    val json = message.json
    def content = json.obj.get("content").flatMap(_.strOpt).getOrElse("")
    json("role").str match
      case "system" => Message.System(content)
      case "user"   => Message.User(Content.TextContent(content))
      case "assistant" =>
        val calls = json.obj.get("tool_calls").toSeq.flatMap(_.arr).map { call =>
          val function = call("function")
          OpenAIToolCall.FunctionToolCall(
            id = Some(call("id").str),
            function = FunctionCall(
              arguments = function("arguments") match
                case ujson.Str(value) => value
                case value            => ujson.write(value),
              name = Some(function("name").str)
            )
          )
        }
        Message.Assistant(content, toolCalls = calls)
      case "tool" => Message.Tool(content, json("tool_call_id").str)
      case role   => throw IllegalArgumentException(s"Unsupported message role: $role")

  private[agent] def toTool(tool: ToolDefinition): Tool =
    val parameters = io.circe.parser.parse(ujson.write(tool.function.parameters))
      .fold(throw _, identity)
    Tool.Function(
      name = tool.function.name,
      description = Some(tool.function.description),
      parameters = parameters.asObject.map(_.toMap),
      strict = None
    )

  def request[T: Writer](
      model: String,
      baseUrl: String,
      apiKey: Option[String],
      messages: Seq[ChatMessage],
      tools: Vector[ToolDefinition],
      toolParsers: Map[String, ReadWriter[T]],
      cancellationToken: CancellationToken = CancellationToken.Never
  ): ChunkRequest[Chunk[T]] =
    val baseUri = Uri.unsafeParse(baseUrl.stripSuffix("/"))
    require(Set("http", "https").contains(baseUri.scheme.getOrElse("")),
      "CAPYBARA_BASE_URL must be an absolute HTTP(S) API base URL")
    val client = new OpenAI(apiKey.getOrElse(""), baseUri)
    val body = ChatBody(
      messages = messages.map(toMessage),
      model = ChatCompletionModel.CustomChatCompletionModel(model),
      tools = Option.when(tools.nonEmpty)(tools.map(toTool)),
      streamOptions = Some(StreamOptions(includeUsage = Some(true)))
    )
    val output = new ChunkOutput[Chunk[T]]()
    val streaming = client.createChatCompletionAsInputStream(body)
    val authenticated =
      if apiKey.isDefined then streaming
      else streaming.withHeaders(streaming.headers.filterNot(_.name.equalsIgnoreCase("Authorization")))
    val request = authenticated.response(asInputStream { stream =>
      val cancellation = cancellationToken.onCancel(stream.close())
      try
        Using.resource(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { reader =>
          val decoder = new StreamDecoder(toolParsers, output)
          val event = new StringBuilder
          def dispatch(): Unit =
            if event.nonEmpty then
              decoder.accept(event.toString)
              event.clear()
          var line = reader.readLine()
          while line != null && output.isRunning && !cancellationToken.isCancelled do
            if line.isEmpty then dispatch()
            else if line.startsWith("data:") then
              if event.nonEmpty then event.append('\n')
              event.append(line.stripPrefix("data:").stripPrefix(" "))
            if output.isRunning && !cancellationToken.isCancelled then line = reader.readLine()
          if cancellationToken.isCancelled then output.close()
          else
            dispatch()
            if output.isRunning then output.fail("unexpected end of OpenAI completion stream")
        }
      catch
        case NonFatal(error) =>
          if cancellationToken.isCancelled then output.close()
          else output.fail(s"OpenAI completion stream failed: ${error.getMessage}")
      finally cancellation.close()
    }.map {
      case Left(error) => Result.Err(s"OpenAI completion request failed: $error")
      case Right(_) =>
        if cancellationToken.isCancelled then Result.Err("cancelled")
        else Result.Ok(())
    })
    ChunkRequest(request, output, cancellationToken)

  /** Tool arguments are JSON fragments, and may be interleaved across call indexes. */
  private[agent] final class StreamDecoder[T](
      toolParsers: Map[String, ReadWriter[T]],
      output: ChunkOutput[Chunk[T]]
  ):
    private final class PendingCall:
      val id = new StringBuilder
      val name = new StringBuilder
      val arguments = new StringBuilder

    private val calls = mutable.TreeMap.empty[Int, PendingCall]
    private var usage = Usage.Empty
    private var finished = false

    def accept(data: String): Unit =
      if data == "[DONE]" then
        require(finished, "completion stream ended without a finish reason")
        val parsed = calls.valuesIterator.map { call =>
          require(call.id.nonEmpty && call.name.nonEmpty, "incomplete streamed tool call")
          parseArguments(call.name.toString, ujson.read(call.arguments.toString), toolParsers) match
            case Result.Ok(arguments) =>
              ToolCall(call.name.toString, arguments, Some(call.id.toString))
            case Result.Err(error) => throw IllegalArgumentException(s"Invalid tool call: $error")
        }.toVector
        if parsed.nonEmpty then output.push(Chunk.Tools(parsed))
        output.close(usage)
      else
        val json = ujson.read(data)
        json.obj.get("error").foreach(error =>
          throw IllegalArgumentException(s"Provider error: ${ujson.write(error)}")
        )
        json.obj.get("usage").filter(_ != ujson.Null).foreach { value =>
          usage = Usage(
            value.obj.get("prompt_tokens").map(_.num.toInt),
            value.obj.get("completion_tokens").map(_.num.toInt)
          )
        }
        json("choices").arr.foreach { choice =>
          if choice.obj.get("index").forall(_.num.toInt == 0) then
            val delta = choice.obj.getOrElse("delta", ujson.Obj())
            delta.obj.get("content").flatMap(_.strOpt).filter(_.nonEmpty)
              .foreach(text => output.push(Chunk.Content(text)))
            delta.obj.get("reasoning_content").orElse(delta.obj.get("reasoning"))
              .flatMap(_.strOpt).filter(_.nonEmpty)
              .foreach(text => output.push(Chunk.Thinking(text)))
            delta.obj.get("tool_calls").foreach(_.arr.foreach { fragment =>
              val call = calls.getOrElseUpdate(fragment("index").num.toInt, new PendingCall)
              fragment.obj.get("id").flatMap(_.strOpt).foreach(call.id.append(_))
              fragment.obj.get("function").foreach { function =>
                function.obj.get("name").flatMap(_.strOpt).foreach(call.name.append(_))
                function.obj.get("arguments").flatMap(_.strOpt).foreach(call.arguments.append(_))
              }
            })
            choice.obj.get("finish_reason").flatMap(_.strOpt).foreach {
              case "stop" | "tool_calls" => finished = true
              case reason => throw IllegalArgumentException(s"Completion stopped: $reason")
            }
        }
