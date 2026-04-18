package capybara.agent

import capybara.agent.OllamaClient.Chunk
import capybara.agent.OllamaClient.ChunkFinalState
import capybara.agent.OllamaClient.ChunkOutputState
import steps.result.Result
import upickle.default.*
import upickle.implicits.namedTuples.default.given
import upickle.jsonschema.*

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.given

import OllamaClient.ChunkOutput

object ScalaAgent {

  val Libraries = Seq("filesystem" -> """
    |package fs
    |trait Path
    |trait Universe {
    |  def currentDir(): Path
    |  def listFiles(path: Path): Seq[Path]
    |}
    """.stripMargin)

  def renderLibs(libs: Iterable[(String, String)]): String = {
    libs
      .map { case (name, code) =>
        s"""#### $name
        |```scala
        |$code
        |```
        |""".stripMargin
      }
      .mkString("(", ", ", ")")
  }

  val SystemPrompt = s"""You are a helpful agent that can perform actions on behalf of the user.
    |## TOOL CALLING:
    |You only have access to the `run_scala_code` tool.
    |All actions requested by the user are executed using this tool.
    |
    |If the user requests an action to be performed that cannot be performed by available scala definitions,
    |you will refuse to execute it, and explain that the user should install a plugin for this agent that provides the necessary definitions.
    |
    |## `run_scala_code` notes:
    |when using the `run_scala_code` tool, you only have access to:
    |- scala.collection.immutable package
    |  - for lazy sequences, Stream is deprecated, use LazyList instead.
    |- primitive types
    |- java.lang.String
    |- scala.Option
    |- scala.math package
    |- Additionally, mutable collections and reflection are forbidden.
    |- and additionally you must supply the "universe" parameter. Each universe makes available additional API.
    |- `scala_code` argument should be concise and without unnecessary boilerplate.
    |- Assume that the result will be formatted automatically, no need to print it.
    |- in each universe, assume that code snippets will already have neccessary imports, as they have the following
    |  boilerplate prepended:
    |  ```scala
    |  object Universe extends Universe
    |  import Universe.*
    |  ```
    |
    |### available universes
    |
    |${renderLibs(Libraries)}
    |""".stripMargin

  def singleRequest(query: String, log: Logger)(using
      ExecutionContext
  ): Future[Result[Seq[Chunk[(universe: String, scala_code: String)]], String]] = {
    val tool = OllamaClient.toolsDef[(universe: String, scala_code: String)](
      "run_scala_code",
      "Execute a Scala expression. The result will be returned formatted as a human-readable string"
    )
    val request = OllamaClient.request(
      // model = "qwen3.5:35b-a3b-coding-nvfp4",
      model = "qwen3.6:35b-a3b-coding-nvfp4",
      system = SystemPrompt,
      query,
      tools = tool.tools,
      toolParsers = tool.toolParsers
    )
    val chunks = Promise[Result[Seq[Chunk[(universe: String, scala_code: String)]], String]]()
    val chunker = new ChunkOutput.ChunkReader[Chunk[(universe: String, scala_code: String)]]() {
      private val collected =
        new java.util.concurrent.ConcurrentLinkedDeque[Chunk[
          (universe: String, scala_code: String)
        ]]()
      private var seenContent =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      private var seenTools =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      private var thinkingDone =
        new java.util.concurrent.atomic.AtomicBoolean(false)
      private var seenAny =
        new java.util.concurrent.atomic.AtomicBoolean(false)

      private def checkThinkingDone(): Unit =
        if thinkingDone.compareAndSet(false, true) then log.print("\n")

      override def onChunk(chunk: Chunk[(universe: String, scala_code: String)]): Unit =
        seenAny.set(true)
        chunk match {
          case Chunk.Content(content) =>
            checkThinkingDone()
            if seenContent.compareAndSet(false, true) then log.print("-----\n")
            log.print(Console.BOLD + content + Console.RESET)
          case Chunk.Thinking(thinking) =>
            log.print(Console.CYAN + thinking + Console.RESET)
          case Chunk.Tools(tool_calls) =>
            checkThinkingDone()
            if seenTools.compareAndSet(false, true) then log.print("-----\n")
            for scalaCode <- tool_calls do
              log.print(
                s"${Console.YELLOW}${scalaCode.name}[${scalaCode.arguments.universe}]:${Console.RESET}\n"
              )
              log.print(
                s"${Console.YELLOW}```scala${Console.RESET}\n"
              )
              log.print(s"${Console.YELLOW}${scalaCode.arguments.scala_code}${Console.RESET}")
              log.print(s"\n${Console.YELLOW}```${Console.RESET}\n")
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
    // Future { scala.concurrent.blocking(ReplExec.compileSigs(InterfaceLib)) }.flatMap(_ =>
    //   request.send(chunker).flatMap {
    //     case Result.Ok(_)       => chunks.future
    //     case err: Result.Err[?] => Future.successful(err)
    //   }
    // )
  }

}
