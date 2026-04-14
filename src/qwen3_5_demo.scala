//> using toolkit 0.7.0
package capybara.agent

import upickle.default.*
import upickle.implicits.namedTuples.default.given

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.given

import OllamaClient.ChunkOutput

type Qwen3_5_Chunk = (content: String, thinking: String)

def qwen3_5(line: String): Option[(Qwen3_5_Chunk, Boolean)] =
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

def main(query: String) = asyncProgram {
  val request = OllamaClient.request(
    model = "qwen3.5:35b-a3b-coding-nvfp4",
    query,
    qwen3_5
  )
  val chunks = Promise[Either[Exception, List[Qwen3_5_Chunk]]]()
  val chunker = new ChunkOutput.ChunkReader[Qwen3_5_Chunk]() {
    private val collected =
      new java.util.concurrent.ConcurrentLinkedDeque[Qwen3_5_Chunk]()
    private var seenContent =
      new java.util.concurrent.atomic.AtomicBoolean(false)
    private var seenAny =
      new java.util.concurrent.atomic.AtomicBoolean(false)
    override def onChunk(chunk: Qwen3_5_Chunk): Unit =
      seenAny.set(true)
      if chunk.thinking.isEmpty then {
        if seenContent.compareAndSet(false, true) then
          println()
          println("-----")
        print(Console.BOLD + chunk.content + Console.RESET)
      } else {
        print(Console.CYAN + chunk.thinking + Console.RESET)
      }
      collected.add(chunk)

    override def onComplete(didFail: Boolean): Unit =
      if seenAny.get() then println()
      if !didFail then chunks.success(Right(collected.asScala.toList))
      else chunks.success(Left(new Exception("Failed")))
  }
  val _ = request.send(chunker)
  for chunkRes <- chunks.future yield {
    chunkRes.toOption.foreach { chunks =>
      val cs = chunks
      val (content, thinking) =
        (cs.map(_.content).mkString(""), cs.map(_.thinking).mkString(""))
      val pw = new java.io.PrintWriter(java.io.File("out.txt"))
      pw.println("thinking:")
      pw.println("--------")
      pw.println(thinking)
      pw.println("--------")
      pw.println("content:")
      pw.println(content)
      pw.close()
    }
  }
}

@main def run(args: String*): Unit =
  val _ = mainargs.ParserForMethods(this).runOrExit(args)

private def asyncProgram[T](f: Future[T]): Unit =
  val latch = new java.util.concurrent.CountDownLatch(1)
  f.onComplete { _ => latch.countDown() }
  latch.await()
