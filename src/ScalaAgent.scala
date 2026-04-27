package capybara.agent

import capybara.agent.OllamaClient.Chunk
import capybara.agent.OllamaClient.ChunkFinalState
import capybara.agent.OllamaClient.ChunkOutputState
import os.pwd
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

  private val skillsDir = pwd / "skills"

  def renderSkills(skills: Seq[Skill]): String = {
    Skills.renderPrompt("\n\n", skills)
  }
  val skills =
    try Skills.loadAll(skillsDir)
    catch { case _: IllegalArgumentException => Nil }
  val SystemPrompt = {
    s"""|## TOOL CALLING:
        |You only have access to the `run_scala_code` tool.
        |All actions requested by the user are executed using this tool.
        |
        |### Available Universes:
        |
        |The `universe` argument tells the system to load some definitions into the environment.
        |Each universe expects a calling convention.
        |
        |${renderSkills(skills)}
        |""".stripMargin
  }

  def singleRequest(query: String, log: Logger)(using
      ExecutionContext
  ): Future[Result[Seq[Chunk[(universe: String, scala_code: String)]], String]] = {
    val tool = OllamaClient.toolsDef[(universe: String, scala_code: String)](
      "run_scala_code",
      """Execute a Scala expression.
        |The system does not have access to side-effecting operations such as `println` or file I/O
        |unless explicitly provided by the requested `universe`.
        |Assume access to scala collections, and error handling capabilities.
        |The tool will also automatically convert any value computed from the expression into a readable format before returning to the user.
        |""".stripMargin
    )
    Console.err.println(s"${Console.MAGENTA}[DEBUG] System Prompt:\n$SystemPrompt${Console.RESET}")
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
    Future
      .sequence(
        skills.map(skill => Future { scala.concurrent.blocking(ReplExec.compileSkill(skill)) })
      )
      .flatMap(_ =>
        request.send(chunker).flatMap {
          case Result.Ok(_)       => chunks.future
          case err: Result.Err[?] => Future.successful(err)
        }
      )
  }

}
