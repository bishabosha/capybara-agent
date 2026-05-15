package capybara.agent

import steps.result.Result
import steps.result.Result.eval.{ok}
import sttp.client4.Request
import sttp.client4.quick.*
import upickle.default.*
import upickle.implicits.namedTuples.default.given

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import upickle.jsonschema.JsonSchema
import scala.deriving.Mirror
import scala.NamedTuple.AnyNamedTuple

object OllamaClient {

  final case class Usage(
      promptEvalCount: Option[Int] = None,
      evalCount: Option[Int] = None
  )

  object Usage:
    val Empty: Usage = Usage()

  inline def toolsDef[T: {ReadWriter, Mirror.Of}](name: String, description: String) =
    given JsonSchema[T] = JsonSchema.derived[T]
    val schema = upickle.jsonschema.schema(upickle.default)[T]
    val schemaArg = schema("$defs").obj.head(1)
    (
      tools = Vector(
        (
          `type` = "function",
          function = (
            name = name,
            parameters = schemaArg,
            description = description
          )
        )
      ),
      toolParsers = Map(
        name -> upickle.default.readwriter[T]
      )
    )

  def readSafe[T](line: ujson.Readable)(using r: Reader[T]): Result[T, String] =
    Result.catchException({
      case err: upickle.core.TraceVisitor.TraceException =>
        err.getCause match
          case cause: Throwable => s"at ${err.jsonPath}: ${cause.getMessage()}"
          case null             => s"at ${err.jsonPath}"
      case err =>
        s"from exception: ${err}"
    })(upickle.default.read[T](line))

  sealed trait ChunkFinalState { self: ChunkOutputState => }

  enum ChunkOutputState {
    case Done extends ChunkOutputState, ChunkFinalState
    case Error(msg: String) extends ChunkOutputState, ChunkFinalState
    case Running
    case Append

    def isError: Boolean = this match {
      case Error(_) => true
      case _        => false
    }

    def isDone: Boolean = this match {
      case Done => true
      case _    => false
    }
  }

  object ChunkOutput {
    abstract class ChunkReader[Chunk]() {
      def onComplete(finalState: ChunkOutputState & ChunkFinalState, usage: Usage): Unit
      def onChunk(chunk: Chunk): Unit
    }
  }

  class ChunkOutput[Chunk] {
    private val state =
      new AtomicReference[ChunkOutputState](ChunkOutputState.Running)
    private val listeners =
      new AtomicReference[List[ChunkOutput.ChunkReader[Chunk]]](Nil)
    private[OllamaClient] def registerListener(
        listener: ChunkOutput.ChunkReader[Chunk]
    ) =
      listeners.updateAndGet(listener :: _)
    def isRunning: Boolean = {
      val curr = state.get()
      !(curr.isDone || curr.isError)
    }
    def failed(): Boolean = state.get().isError
    def close(usage: Usage = Usage.Empty) =
      if state.compareAndSet(ChunkOutputState.Running, ChunkOutputState.Done)
      then listeners.get().foreach(_.onComplete(ChunkOutputState.Done, usage))
      else ()
    def fail(message: String) =
      val errorState = ChunkOutputState.Error(message)
      if state.compareAndSet(ChunkOutputState.Running, errorState)
      then listeners.get().foreach(_.onComplete(errorState, Usage.Empty))
      else ()
    def push(chunk: Chunk) =
      if (state.compareAndSet(ChunkOutputState.Running, ChunkOutputState.Append)) {
        listeners.get().foreach(_.onChunk(chunk))
        state.set(ChunkOutputState.Running)
      } else {
        throw new IllegalStateException("push called while not running")
      }
  }

  class ChunkRequest[Chunk](
      inner: Request[Result[Unit, String]],
      output: ChunkOutput[Chunk],
      cancellationToken: CancellationToken
  ):
    def send(
        listeners: ChunkOutput.ChunkReader[Chunk]*
    )(using ExecutionContext): Future[Result[Unit, String]] =
      if cancellationToken.isCancelled then
        output.close()
        Future.successful(Result.Err("cancelled"))
      else if output.isRunning then
        Future {
          blocking {
            listeners.foreach(output.registerListener)
            inner.send().body
          }
        }
      else Future.failed(new java.io.IOException("output already done"))

  enum Chunk[+T] {
    case Content(content: String)
    case Thinking(thinking: String)
    case Tools(toolCalls: Vector[ToolCall[T]])
  }

  enum ResolvedChunk[+T, +R] {
    case Content(content: String) extends ResolvedChunk[Nothing, Nothing]
    case Thinking(thinking: String) extends ResolvedChunk[Nothing, Nothing]
    case Tools(toolCalls: Vector[(ToolCall[T], R)]) extends ResolvedChunk[T, R]
  }

  final case class ToolCall[+T](
      name: String,
      arguments: T
  )

  final case class ChatMessage(json: ujson.Value)

  object ChatMessage {
    def apply[T <: AnyNamedTuple: Writer](obj: T): ChatMessage =
      ChatMessage(writeJs(obj))

    def system(content: String): ChatMessage =
      ChatMessage(
        (
          role = "system",
          content = content
        )
      )

    def user(content: String): ChatMessage =
      ChatMessage(
        (
          role = "user",
          content = content
        )
      )

    def assistant[T: Writer](
        thinking: String,
        content: String,
        toolCalls: Vector[ToolCall[T]]
    ): ChatMessage = {
      val message = writeJs {
        (
          role = "assistant",
          content = content
        )
      }
      if thinking.nonEmpty then message("thinking") = thinking
      if toolCalls.nonEmpty then
        message("tool_calls") = writeJs {
          toolCalls.zipWithIndex.map { case (toolCall, index) =>
            (
              `type` = "function",
              function = (
                index = index,
                name = toolCall.name,
                arguments = toolCall.arguments
              )
            )
          }
        }
      ChatMessage(message)
    }

    def tool(toolName: String, content: String): ChatMessage =
      ChatMessage(
        (
          role = "tool",
          tool_name = toolName,
          content = content
        )
      )
  }

  def parseArguments[T](
      name: String,
      arguments: ujson.Value,
      lookup: Map[String, ReadWriter[T]]
  ): Result[T, String] =
    lookup.get(name) match
      case Some(given ReadWriter[t]) => readSafe[t](arguments)
      case None                      => Result.Err("unknown tool name")

  def chunkParser[T](
      line: String,
      tools: Map[String, ReadWriter[T]]
  ): Result[(message: Chunk[T], done: Boolean, usage: Usage), String] =
    val messageState = readSafe[(message: ujson.Value, done: Boolean)](line)
    def thinkingState(msg: ujson.Value) = readSafe[(thinking: String)](msg).tap(thinking =>
      require(thinking.thinking.nonEmpty, "unexpected empty thinking")
    )
    def contentState(msg: ujson.Value) = readSafe[(content: String)](msg)
    def toolState(msg: ujson.Value) =
      readSafe[(tool_calls: Vector[(function: ujson.Value)])](msg).tap(calls =>
        require(calls.tool_calls.nonEmpty, "unexpected empty tool calls")
      )
    def toolCallState(toolCall: ujson.Value): Result[ToolCall[T], String] =
      for
        name <- readSafe[(name: String)](toolCall)
        arguments <- readSafe[(arguments: ujson.Value)](toolCall)
        parsed <- parseArguments(name.name, arguments.arguments, tools)
      yield ToolCall(name.name, parsed)
    def intField(json: ujson.Value, name: String): Option[Int] =
      json.obj.get(name).flatMap {
        case ujson.Num(value) => Some(value.toInt)
        case ujson.Str(value) => value.toIntOption
        case _                => None
      }
    def usageState: Usage =
      try
        val json = ujson.read(line)
        Usage(
          promptEvalCount = intField(json, "prompt_eval_count"),
          evalCount = intField(json, "eval_count")
        )
      catch case _: Throwable => Usage.Empty
    for
      msg <- messageState
      content <- contentState(msg.message)
      message <- locally {
        (thinkingState(msg.message), toolState(msg.message)) match
          case (Result.Ok((thinking = t)), Result.Ok((tool_calls = calls))) =>
            Result.Err(s"unexpected thinking and tool_calls!")
          case (Result.Ok((thinking = t)), _) =>
            if content.content.nonEmpty then Result.Err(s"unexpected thinking and content!")
            else Result.Ok(Chunk.Thinking(t))
          case (_, Result.Ok((tool_calls = calls))) =>
            if content.content.nonEmpty then Result.Err(s"unexpected tool_calls and content!")
            else Result(Chunk.Tools(calls.map(call => toolCallState(call.function).ok)))
          case _ => Result.Ok(Chunk.Content(content.content))
      }
    yield (message, msg.done, usageState)

  def request[T: Writer](
      model: String,
      messages: Seq[ChatMessage],
      tools: Vector[
        (`type`: String, function: (name: String, parameters: ujson.Value, description: String))
      ],
      toolParsers: Map[String, ReadWriter[T]],
      contextWindow: Int,
      keepAlive: String,
      cancellationToken: CancellationToken = CancellationToken.Never
  ): ChunkRequest[Chunk[T]] = {
    val output = new ChunkOutput[Chunk[T]]()
    val bodyObj =
      (
        model = model,
        messages = messages.map(_.json),
        tools = tools,
        options = (num_ctx = contextWindow),
        keep_alive = keepAlive
      )
    val req = quickRequest
      .post(uri"http://localhost:11434/api/chat")
      .body(
        write(
          bodyObj
        )
      )
      .response(
        asInputStream({ is =>
          scala.util.Using.Manager { m =>
            val isr =
              m(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))
            val reader = m(new java.io.BufferedReader(isr))
            var completed = cancellationToken.isCancelled
            var line: String | Null = null
            while !completed do
              line = reader.readLine()
              if cancellationToken.isCancelled then
                output.close()
                completed = true
              else if line == null then completed = true
              else
                val parsed = chunkParser(line, toolParsers)
                parsed match
                  case Result.Ok((chunk, d, usage)) =>
                    output.push(chunk)
                    if d then output.close(usage)
                    completed = d
                  case Result.Err(error) =>
                    output.fail(s"while reading response chunk: $error\n[debug]: $line")
                    completed = true
            if cancellationToken.isCancelled then output.close()
            else if line == null then output.fail("unexpected end of stream")
          } match {
            case scala.util.Success(_) =>
              ()
            case scala.util.Failure(e) =>
              output.fail(s"from exception: $e")
          }
        }).map({
          case Left(err) =>
            readSafe[(error: String)](err).match
              case Result.Ok(msg)    => Result.Err(s"request failed: ${msg.error}")
              case Result.Err(error) => Result.Err(s"could not parse request failure: $error")
          case Right(_) =>
            if cancellationToken.isCancelled then Result.Err("cancelled")
            else Result.Ok(())
        })
      )
    ChunkRequest(req, output, cancellationToken)
  }
}
