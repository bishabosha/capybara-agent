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
import capybara.agent.OllamaClient.ResolvedChunk
import ReplExec.ScalaToolResult

object ScalaAgent {

  private val skillsDir = pwd / "skills"
  private val builtinSkillsDir = pwd / "builtin-skills"

  def renderSkills(skills: Seq[Skill]): String = {
    Skills.renderPrompt("\n\n", skills)
  }
  val skills =
    try Skills.loadAll(skillsDir)
    catch { case _: IllegalArgumentException => Nil }
  val builtinSkill =
    Skills.loadAll(builtinSkillsDir).filter(_.universe == "capybara-builtins").head
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
  ): Future[Result[Seq[
    ResolvedChunk[(universe: String, scala_code: String), ScalaToolResult]
  ], String]] = {
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
    val chunks = Promise[
      (
          collected: Result[Seq[
            (Int, Chunk[(universe: String, scala_code: String)])
          ], String],
          scalaCommands: Map[Int, Promise[ScalaToolResult]]
      )
    ]()
    val chunker = new ChunkOutput.ChunkReader[Chunk[(universe: String, scala_code: String)]]() {
      private val collected =
        new java.util.concurrent.ConcurrentLinkedDeque[
          (
              Int,
              Chunk[
                (universe: String, scala_code: String)
              ]
          )
        ]()
      private val commandCounter = new java.util.concurrent.atomic.AtomicInteger(0)
      private val scalaCommands =
        new java.util.concurrent.ConcurrentHashMap[Int, Promise[ScalaToolResult]]()
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
        var chunkId = -1
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
            chunkId =
              // later we retrieve based on the offset from the first tool call
              commandCounter.get() + 1
            for scalaCode <- tool_calls do
              // todo: extract tool call id from llm?
              val promise = Promise[ScalaToolResult]()
              val callId = commandCounter.incrementAndGet()
              scalaCommands.put(callId, promise)
              promise.tryCompleteWith {
                skills.find(_.universe == scalaCode.arguments.universe) match
                  case Some(skill) =>
                    ReplExec.runCodeHarness(
                      builtinSkill,
                      skill,
                      scalaCode.arguments.scala_code,
                      callId,
                      log
                    )
                  case None =>
                    Future.successful(
                      ScalaToolResult.Failure(
                        s"Universe not found: ${scalaCode.arguments.universe}"
                      )
                    )
              }
              log.print(
                s"${Console.YELLOW}${scalaCode.name}[${scalaCode.arguments.universe}]:${Console.RESET}\n"
              )
              log.print(
                s"${Console.YELLOW}```scala${Console.RESET}\n"
              )
              log.print(s"${Console.YELLOW}${scalaCode.arguments.scala_code}${Console.RESET}")
              log.print(s"\n${Console.YELLOW}```${Console.RESET}\n")
        }
        collected.add((chunkId, chunk))

      override def onComplete(finalState: ChunkOutputState & ChunkFinalState): Unit =
        if seenAny.get() then log.print("\n")
        chunks.success(
          finalState match
            case ChunkOutputState.Done =>
              (Result.Ok(collected.asScala.toVector), scalaCommands.asScala.toMap)
            case ChunkOutputState.Error(msg) => (Result.Err(msg), scalaCommands.asScala.toMap)
        )
    }
    Future
      .sequence(
        (builtinSkill +: skills)
          .map(skill => Future { scala.concurrent.blocking(ReplExec.compileSkill(skill)) })
      )
      .flatMap(_ =>
        request.send(chunker).flatMap {
          case Result.Ok(_) =>
            for
              cs <- chunks.future
              results <- Future.sequence(cs.scalaCommands.map((k, p) => p.future.map(r => (k, r))))
            yield cs.collected.map({ ress =>
              ress.map({ (id, chunk) =>
                chunk match
                  case Chunk.Content(content)   => ResolvedChunk.Content(content)
                  case Chunk.Thinking(thinking) => ResolvedChunk.Thinking(thinking)
                  case Chunk.Tools(tool_calls)  =>
                    ResolvedChunk.Tools(
                      tool_calls.zipWithIndex.map { case (tc, idx) =>
                        (
                          tc,
                          results.toMap.getOrElse(id + idx, ScalaToolResult.Failure("No response"))
                        )
                      }.toVector
                    )
              })
            })
          case err: Result.Err[?] => Future.successful(err)
        }
      )
  }

}
