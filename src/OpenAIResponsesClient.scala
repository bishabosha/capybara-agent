package capybara.agent

import OllamaClient.*
import sttp.ai.openai.OpenAI
import sttp.ai.openai.requests.responses.{ResponsesModel, ResponsesRequestBody, Tool}
import sttp.ai.openai.requests.responses.ResponsesRequestBody.Input
import sttp.model.Uri
import steps.result.Result
import upickle.default.*

import scala.collection.mutable

object OpenAIResponsesClient:
  private def text(json: ujson.Value, key: String): String =
    json.obj.get(key).flatMap(_.strOpt).getOrElse("")

  private def arguments(value: ujson.Value): String = value match
    case ujson.Str(value) => value
    case value            => ujson.write(value)

  private[agent] def toInput(message: ChatMessage): List[Input] =
    message.responseItems match
      case Some(items) => items.toList.map(toInputItem)
      case None =>
        val json = message.json
        val content = text(json, "content")
        json("role").str match
          case "tool" => List(Input.FunctionCallOutput(json("tool_call_id").str, content))
          case role @ ("system" | "user" | "assistant") =>
            val messages =
              if content.nonEmpty || role != "assistant" then
                List(Input.InputMessage(List(Input.InputContentItem.InputText(content)), role, None))
              else Nil
            val calls = json.obj.get("tool_calls").toList.flatMap(_.arr).map { call =>
              val function = call("function")
              Input.FunctionCall(
                arguments(function("arguments")), call("id").str, function("name").str)
            }
            messages ++ calls
          case role => throw IllegalArgumentException(s"Unsupported message role: $role")

  /** Replay completed output items, including encrypted reasoning, in the next request. */
  private[agent] def toInputItem(item: ujson.Value): Input =
    item("type").str match
      case "function_call" =>
        Input.FunctionCall(item("arguments").str, item("call_id").str, item("name").str,
          id = item.obj.get("id").flatMap(_.strOpt),
          status = item.obj.get("status").flatMap(_.strOpt))
      case "reasoning" =>
        Input.Reasoning(
          id = item("id").str,
          summary = item.obj.get("summary").toList.flatMap(_.arr)
            .map(value => Input.Reasoning.SummaryText(value("text").str)),
          encryptedContent = item.obj.get("encrypted_content").flatMap(_.strOpt),
          status = item.obj.get("status").flatMap(_.strOpt))
      case "message" =>
        val content = item("content").arr.toList.map { part =>
          part("type").str match
            case "output_text" => Input.OutputContentItem.OutputText(Nil, part("text").str)
            case "refusal" => Input.OutputContentItem.Refusal(part("refusal").str)
            case kind => throw IllegalArgumentException(s"Unsupported Responses content: $kind")
        }
        Input.OutputMessage(content, item("id").str, item("role").str,
          item.obj.get("status").flatMap(_.strOpt).getOrElse("completed"))
      case kind => throw IllegalArgumentException(s"Unsupported Responses output item: $kind")

  private[agent] def requestBody(
      model: String,
      messages: Seq[ChatMessage],
      tools: Vector[ToolDefinition]
  ): ResponsesRequestBody =
    val functions = tools.map { tool =>
      val parameters = io.circe.parser.parse(ujson.write(tool.function.parameters))
        .fold(throw _, identity).asObject.getOrElse(
          throw IllegalArgumentException("Function parameters must be a JSON object"))
      Tool.Function(tool.function.name, parameters.toMap, strict = false,
        description = Some(tool.function.description))
    }.toList
    ResponsesRequestBody(
      model = Some(ResponsesModel.CustomResponsesModel(model)),
      input = Some(Right(messages.toList.flatMap(toInput))),
      tools = Option.when(functions.nonEmpty)(functions),
      store = Some(false),
      include = Some(List("reasoning.encrypted_content"))
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
    val output = new ChunkOutput[Chunk[T]]()
    val decoder = new StreamDecoder(toolParsers, output)
    OpenAICompatibleClient.streamRequest(
      client.createModelResponseAsInputStream(requestBody(model, messages, tools)),
      apiKey, output, decoder.accept, cancellationToken)

  private[agent] final class StreamDecoder[T](
      toolParsers: Map[String, ReadWriter[T]],
      output: ChunkOutput[Chunk[T]]
  ):
    private val streamedText = mutable.Set.empty[(Int, Int)]
    private val streamedReasoning = mutable.Set.empty[(Int, Int)]

    private def position(event: ujson.Value, partKey: String): (Int, Int) =
      (event("output_index").num.toInt, event(partKey).num.toInt)

    private def finish(response: ujson.Value): Unit =
      require(response("status").str == "completed", "Responses request did not complete")
      val items = response("output").arr.toVector
      // Validate the complete response before executing any tool, including when
      // argument deltas from multiple calls were interleaved in the stream.
      items.foreach(toInputItem)
      val calls = items.filter(item => item("type").str == "function_call").map { item =>
        val name = item("name").str
        val callId = item("call_id").str
        require(callId.nonEmpty && name.nonEmpty, "incomplete Responses tool call")
        parseArguments(name, ujson.read(item("arguments").str), toolParsers) match
          case Result.Ok(arguments) => ToolCall(name, arguments, Some(callId))
          case Result.Err(error) => throw IllegalArgumentException(s"Invalid tool call: $error")
      }
      val usage = response.obj.get("usage").filter(_ != ujson.Null).map { value =>
        Usage(value.obj.get("input_tokens").map(_.num.toInt),
          value.obj.get("output_tokens").map(_.num.toInt))
      }.getOrElse(Usage.Empty)
      items.zipWithIndex.foreach { (item, index) =>
        item("type").str match
          case "message" =>
            item("content").arr.zipWithIndex.foreach { (part, partIndex) =>
              val value = if part("type").str == "refusal" then text(part, "refusal") else text(part, "text")
              if value.nonEmpty && !streamedText((index, partIndex)) then output.push(Chunk.Content(value))
            }
          case "reasoning" =>
            item.obj.get("summary").foreach(_.arr.zipWithIndex.foreach { (part, partIndex) =>
              val value = text(part, "text")
              if value.nonEmpty && !streamedReasoning((index, partIndex)) then output.push(Chunk.Thinking(value))
            })
          case _ => ()
      }
      output.push(Chunk.ResponseItems(items))
      if calls.nonEmpty then output.push(Chunk.Tools(calls))
      output.close(usage)

    def accept(data: String): Unit =
      require(data != "[DONE]", "Responses stream ended before response.completed")
      val event = ujson.read(data)
      event("type").str match
        case "response.output_text.delta" | "response.refusal.delta" =>
          val delta = text(event, "delta")
          if delta.nonEmpty then
            streamedText += position(event, "content_index")
            output.push(Chunk.Content(delta))
        case "response.reasoning_summary_text.delta" =>
          val delta = text(event, "delta")
          if delta.nonEmpty then
            streamedReasoning += position(event, "summary_index")
            output.push(Chunk.Thinking(delta))
        case "response.completed" => finish(event("response"))
        case "response.failed" | "response.incomplete" | "response.cancelled" | "error" =>
          throw IllegalArgumentException(s"Responses request failed: ${ujson.write(event)}")
        // Lifecycle and argument events are followed by canonical completed items.
        case _ => ()
