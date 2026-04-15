package capybara.agent

import OllamaClient.ChunkOutput

import upickle.default.*
import upickle.implicits.namedTuples.default.given

import scala.concurrent.{ExecutionContext, Promise}
import scala.jdk.CollectionConverters.given
import scala.concurrent.Future
import steps.result.Result
import capybara.agent.OllamaClient.ChunkOutputState
import capybara.agent.OllamaClient.ChunkFinalState
import capybara.agent.OllamaClient.readSafe

object Qwen3_5 {
  enum Chunk {
    case Content(content: String)
    case Thinking(thinking: String)
  }

  def qwen3_5(line: String): Result[(message: Chunk, done: Boolean), String] =
    readSafe[
      (
          message: (thinking: String),
          done: Boolean
      )
    ](line)
      .map(c =>
        (
          message = Chunk.Thinking(thinking = c.message.thinking),
          done = c.done
        )
      )
      .or(
        readSafe[
          (
              message: (content: String),
              done: Boolean
          )
        ](line)
          .map(c =>
            (
              message = Chunk.Content(content = c.message.content),
              done = c.done
            )
          )
      )

  def singleRequest(query: String, log: Logger)(using
      ExecutionContext
  ): Future[Result[Seq[Chunk], String]] = {
    val request = OllamaClient.request(
      model = "qwen3.5:35b-a3b-coding-nvfp4",
      query,
      qwen3_5
    )
    val chunks = Promise[Result[Seq[Chunk], String]]()
    val chunker = new ChunkOutput.ChunkReader[Chunk]() {
      private val collected =
        new java.util.concurrent.ConcurrentLinkedDeque[Chunk]()
      private var seenContent =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      private var seenAny =
        new java.util.concurrent.atomic.AtomicBoolean(false)

      override def onChunk(chunk: Chunk): Unit =
        seenAny.set(true)
        chunk match {
          case Chunk.Content(content) =>
            if seenContent.compareAndSet(false, true) then log.print("\n-----\n")
            log.print(Console.BOLD + content + Console.RESET)
          case Chunk.Thinking(thinking) =>
            log.print(Console.CYAN + thinking + Console.RESET)
        }
        collected.add(chunk)

      override def onComplete(finalState: ChunkOutputState & ChunkFinalState): Unit =
        if seenAny.get() then log.print("\n")
        chunks.success(
          finalState match
            case ChunkOutputState.Done       => Result.Ok(collected.asScala.toVector)
            case ChunkOutputState.Error(msg) => Result.Err(msg)
        )
    }
    request.send(chunker).flatMap {
      case Result.Ok(_)       => chunks.future
      case err: Result.Err[?] => Future.successful(err)
    }
  }

}
