package capybara.agent

import sttp.client4.quick.*
import upickle.default.*
import upickle.implicits.namedTuples.default.given

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import sttp.client4.Request

object OllamaClient {

  enum ChunkOutputState {
    case Done
    case Error
    case Running
    case Append
  }

  object ChunkOutput {
    abstract class ChunkReader[Chunk]() {
      def onComplete(didFail: Boolean): Unit
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
    def failed(): Boolean = state.get() == ChunkOutputState.Error
    def close() =
      if state.compareAndSet(ChunkOutputState.Running, ChunkOutputState.Done)
      then listeners.get().foreach(_.onComplete(didFail = false))
      else throw new IllegalStateException("close called while not running")
    def fail() =
      if state.compareAndSet(ChunkOutputState.Running, ChunkOutputState.Error)
      then listeners.get().foreach(_.onComplete(true))
      else throw new IllegalStateException("fail called while not running")
    def push(chunk: Chunk) =
      if (
        state.compareAndSet(ChunkOutputState.Running, ChunkOutputState.Append)
      ) {
        listeners.get().foreach(_.onChunk(chunk))
        state.set(ChunkOutputState.Running)
      } else {
        throw new IllegalStateException("push called while not running")
      }
  }

  class ChunkRequest[Chunk](
      inner: Request[Either[String, Boolean]],
      output: ChunkOutput[Chunk]
  ):
    def send(
        listeners: ChunkOutput.ChunkReader[Chunk]*
    )(using ExecutionContext): Future[Boolean] = Future {
      blocking {
        listeners.foreach(output.registerListener)
        val r = inner.send()
        r.body match
          case Right(res) => Future.successful(res)
          case Left(e)    => Future.failed(new java.io.IOException(e))
      }
    }.flatten

  def request[Chunk](
      model: String,
      query: String,
      parser: String => Option[(Chunk, Boolean)]
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
              line != null
            do
              done = parser(line) match
                case Some((chunk, d)) =>
                  output.push(chunk)
                  if d then output.close()
                  d
                case None =>
                  output.fail()
                  true
            done
          } match {
            case scala.util.Success(done) => done
            case scala.util.Failure(e)    =>
              output.fail()
              false
          }
        })
      )
    ChunkRequest(req, output)
  }
}
