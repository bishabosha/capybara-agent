package capybara.agent

import steps.result.Result
import sttp.client4.Request
import sttp.client4.quick.*
import upickle.default.*
import upickle.implicits.namedTuples.default.given

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking

object OllamaClient {

  def readSafe[T](line: String)(using r: Reader[T]): Result[T, String] =
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
      def onComplete(finalState: ChunkOutputState & ChunkFinalState): Unit
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
    def close() =
      if state.compareAndSet(ChunkOutputState.Running, ChunkOutputState.Done)
      then listeners.get().foreach(_.onComplete(ChunkOutputState.Done))
      else throw new IllegalStateException("close called while not running")
    def fail(message: String) =
      val errorState = ChunkOutputState.Error(message)
      if state.compareAndSet(ChunkOutputState.Running, errorState)
      then listeners.get().foreach(_.onComplete(errorState))
      else throw new IllegalStateException("fail called while not running")
    def push(chunk: Chunk) =
      if (state.compareAndSet(ChunkOutputState.Running, ChunkOutputState.Append)) {
        listeners.get().foreach(_.onChunk(chunk))
        state.set(ChunkOutputState.Running)
      } else {
        throw new IllegalStateException("push called while not running")
      }
  }

  class ChunkRequest[Chunk](
      inner: Request[Result[Boolean, String]],
      output: ChunkOutput[Chunk]
  ):
    def send(
        listeners: ChunkOutput.ChunkReader[Chunk]*
    )(using ExecutionContext): Future[Result[Boolean, String]] =
      if output.isRunning then
        Future {
          blocking {
            listeners.foreach(output.registerListener)
            inner.send().body
          }
        }
      else Future.failed(new java.io.IOException("output already done"))

  def request[Chunk](
      model: String,
      query: String,
      parser: String => Result[(message: Chunk, done: Boolean), String]
  ): ChunkRequest[Chunk] = {
    val output = new ChunkOutput[Chunk]()
    val req = quickRequest
      .post(uri"http://localhost:11434/api/chat")
      .body(
        write(
          (
            model = model,
            messages = Seq(
              (
                role = "user",
                content = query
              )
            )
          )
        )
      )
      .response(
        asInputStream({ is =>
          scala.util.Using.Manager { m =>
            val isr =
              m(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))
            val reader = m(new java.io.BufferedReader(isr))
            var done = false
            var line: String | Null = null
            while
              line = reader.readLine()
              !done && line != null
            do
              done = parser(line) match
                case Result.Ok((chunk, d)) =>
                  output.push(chunk)
                  if d then output.close()
                  d
                case Result.Err(error) =>
                  output.fail(s"while reading response chunk: $error")
                  true
            done
          } match {
            case scala.util.Success(done) => done
            case scala.util.Failure(e)    =>
              output.fail(s"from exception: $e")
              false
          }
        }).map({
          case Left(err) =>
            readSafe[(error: String)](err).match
              case Result.Ok(msg)    => Result.Err(s"request failed: ${msg.error}")
              case Result.Err(error) => Result.Err(s"could not parse request failure: $error")
          case Right(done) => Result.Ok(done)
        })
      )
    ChunkRequest(req, output)
  }
}
