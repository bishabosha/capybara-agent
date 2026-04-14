package capybara.agent

import OllamaClient.ChunkOutput

import upickle.default.*
import upickle.implicits.namedTuples.default.given

import scala.concurrent.{ExecutionContext, Promise}
import scala.jdk.CollectionConverters.given

object Qwen3_5 {
  type Chunk = (content: String, thinking: String)

  def qwen3_5(line: String): Option[(Chunk, Boolean)] =
    scala.util
      .Try(
        read[
          (
              message: (content: String, thinking: String),
              done: Boolean
          )
        ](line)
      )
      .orElse(
        scala.util
          .Try(
            read[
              (
                  message: (content: String),
                  done: Boolean
              )
            ](line)
          )
          .map(c =>
            (
              message = (content = c.message.content, thinking = ""),
              done = c.done
            )
          )
      )
      .map(chunk => (chunk.message -> chunk.done))
      .toOption

  def singleRequest(query: String, log: Logger)(using ExecutionContext) = {
    val request = OllamaClient.request(
      model = "qwen3.5:35b-a3b-coding-nvfp4",
      query,
      qwen3_5
    )
    val chunks = Promise[Either[Exception, Seq[Chunk]]]()
    val chunker = new ChunkOutput.ChunkReader[Chunk]() {
      private val collected =
        new java.util.concurrent.ConcurrentLinkedDeque[Chunk]()
      private var seenContent =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      private var seenAny =
        new java.util.concurrent.atomic.AtomicBoolean(false)

      override def onChunk(chunk: Chunk): Unit =
        seenAny.set(true)
        if chunk.thinking.isEmpty then {
          if seenContent.compareAndSet(false, true) then log.print("\n-----\n")
          log.print(Console.BOLD + chunk.content + Console.RESET)
        } else {
          log.print(Console.CYAN + chunk.thinking + Console.RESET)
        }
        collected.add(chunk)

      override def onComplete(didFail: Boolean): Unit =
        if seenAny.get() then log.print("\n")
        if !didFail then chunks.success(Right(collected.asScala.toVector))
        else chunks.success(Left(new Exception("Failed")))
    }
    val _ = request.send(chunker)
    chunks.future
  }

}
