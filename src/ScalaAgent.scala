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

  type ScalaToolCall = (universe: String, scala_code: String)
  type ScalaResolvedChunk = ResolvedChunk[ScalaToolCall, ScalaToolResult]
  final case class AgentResponse(chunks: Seq[ScalaResolvedChunk], usage: OllamaClient.Usage)
  private type RawScalaChunk = (Int, Chunk[ScalaToolCall])
  private final case class RawAgentResponse(chunks: Seq[RawScalaChunk], usage: OllamaClient.Usage)

  private val skillsDir = pwd / "skills"
  private val builtinSkillsDir = pwd / "builtin-skills"
  val Model = "qwen3.6:35b-a3b-coding-nvfp4"
  val DefaultContextWindow = 32768
  val DefaultKeepAlive = "30m"
  private val ContextWindowEnv = "CAPYBARA_CONTEXT_WINDOW"
  private val KeepAliveEnv = "CAPYBARA_KEEP_ALIVE"

  enum ThinkingMode:
    case On, Off, Auto

    def displayName: String =
      this match
        case On   => "on"
        case Off  => "off"
        case Auto => "auto"

  object ThinkingMode:
    def parse(value: String): Option[ThinkingMode] =
      value.trim.toLowerCase match
        case "on" | "true" | "yes"     => Some(On)
        case "off" | "false" | "no"    => Some(Off)
        case "auto" | "automatic" | "" => Some(Auto)
        case _                         => None

  def shouldThink(query: String, mode: ThinkingMode): Boolean =
    mode match
      case ThinkingMode.On   => true
      case ThinkingMode.Off  => false
      case ThinkingMode.Auto =>
        val q = query.toLowerCase
        def hasAny(words: String*): Boolean =
          words.exists(q.contains)

        val explicitOff =
          hasAny("don't think", "dont think", "no thinking", "think off", "quick", "just ")
        val explicitOn =
          hasAny(
            "think carefully",
            "reason through",
            "analyze",
            "analyse",
            "debug",
            "design",
            "architecture",
            "tradeoff",
            "trade-off"
          )
        val likelyBasic =
          hasAny(
            "calculate",
            "convert",
            "format",
            "sort",
            "sum",
            "list"
          )
        val likelyComplex =
          hasAny(
            "why",
            "fix",
            "failing",
            "failed",
            "error",
            "exception",
            "refactor",
            "implement",
            "multiple files",
            "across files",
            "codebase",
            "root cause",
            "test failure"
          ) || q.length > 500

        if explicitOff then false
        else if explicitOn then true
        else if likelyBasic && !likelyComplex then false
        else likelyComplex

  def configuredContextWindow: Int =
    sys.env
      .get(ContextWindowEnv)
      .flatMap(_.toIntOption)
      .filter(_ > 0)
      .getOrElse(DefaultContextWindow)

  def configuredKeepAlive: String =
    sys.env
      .get(KeepAliveEnv)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(DefaultKeepAlive)

  def renderSkills(skills: Seq[Skill]): String = {
    Skills.renderPrompt("\n\n", skills)
  }
  val skills =
    try Skills.loadAll(skillsDir)
    catch { case _: IllegalArgumentException => Nil }
  val availableSkills = Skills.Basic +: skills
  val builtinSkill =
    Skills.loadAll(builtinSkillsDir).filter(_.universe == "capybara-builtins").head
  val SystemPrompt = {
    s"""|## TOOL CALLING:
        |You only have access to the `run_scala_code` tool.
        |All actions requested by the user are executed using this tool.
        |
        |### Execution Discipline:
        |
        |When the user asks for a straightforward calculation, data transformation, or standard-library-only answer, immediately call `run_scala_code` with `universe = "basic"`.
        |For `basic` tasks, spend no more than a few tokens deciding; do not reason step-by-step internally.
        |Do not first explain that you will call the tool.
        |Never write pre-tool narration such as "I will execute", "I will call the tool", "One detail", or notes about whether standard library methods are available.
        |If you are about to say that you will execute code, do not say it; make the tool call instead.
        |If you have already said an intent-to-execute sentence once, never repeat it; make the tool call or ask one concise clarification question.
        |Do not repeat the same sentence, paragraph, tool-call intent, or reasoning fragment. If you notice repetition, stop immediately and either make the tool call, ask one concise clarification question, or provide the final answer.
        |Never intentionally produce an infinite loop. Every response should make progress toward a tool call, a clarification question, or the final answer.
        |Do not put Scala code in a markdown code block unless the user explicitly asked to see code.
        |Do not deliberate over algorithm choices for small basic tasks; choose the simplest correct implementation and execute it.
        |For simple sequence requests, generate exactly the requested amount of data unless the user asks for more.
        |For follow-up requests, infer omitted nouns and context from the conversation history when the reference is clear.
        |Treat vague quality words like "optimized", "fast", "clean", or "simple" as preferences, not new requirements, unless the user gives a concrete constraint.
        |For small bounded outputs, prefer the obvious standard-library implementation and standard numeric type that fits the requested result.
        |If an ambiguity materially changes the answer, resource usage, or observable behavior, stop and ask one concise clarification question instead of continuing to reason internally.
        |After a successful tool result, answer with the result and only the minimal explanation needed.
        |
        |### Available Universes:
        |
        |The `universe` argument selects the execution context for the code.
        |Default to `basic` for ordinary computation that only needs the Scala or Java standard library.
        |Choose `basic` for calculations, string/list/map transformations, JSON-like data shaping with standard collections, date/time arithmetic, sorting, filtering, and similar local reasoning.
        |Use a skill-specific universe only when the task needs the API described for that universe.
        |Do not choose a skill-specific universe just because the user mentions a path, filename, URL, or command as plain text; choose it only when you must interact with that external resource.
        |Each universe expects its own calling convention.
        |
        |${renderSkills(availableSkills)}
        |""".stripMargin
  }

  def singleRequest(
      query: String,
      log: Logger,
      contextWindow: Int,
      keepAlive: String,
      think: Boolean,
      cancellationToken: CancellationToken = CancellationToken.Never
  )(using
      ExecutionContext
  ): Future[Result[AgentResponse, String]] =
    singleRequest(
      Vector(OllamaClient.ChatMessage.user(query)),
      log,
      contextWindow,
      keepAlive,
      think,
      cancellationToken
    )

  def singleRequest(
      history: Seq[OllamaClient.ChatMessage],
      log: Logger,
      contextWindow: Int,
      keepAlive: String,
      think: Boolean,
      cancellationToken: CancellationToken
  )(using
      ExecutionContext
  ): Future[Result[AgentResponse, String]] = {
    val tool = OllamaClient.toolsDef[ScalaToolCall](
      "run_scala_code",
      """Execute a Scala expression.
        |For simple standard-library-only requests, call this tool immediately with universe "basic"; do not announce or draft the code in normal assistant text first.
        |For universe "basic", avoid extended reasoning; choose a direct expression and call the tool.
        |Do not emit assistant text like "I will execute" before this tool. If execution is the next step, this tool call is the next token-level action.
        |Use universe "basic" for arithmetic, strings, collections, dates, parsing, or other standard-library-only tasks.
        |The system does not have access to side-effecting operations such as file I/O, network I/O, shell commands, or user-visible `println`
        |unless explicitly provided by the requested universe.
        |Return the desired answer as the final expression.
        |The tool will also automatically convert any value computed from the expression into a readable format before returning to the user.
        |""".stripMargin
    )
    val request = OllamaClient.request(
      model = Model,
      messages = OllamaClient.ChatMessage.system(SystemPrompt) +: history,
      tools = tool.tools,
      toolParsers = tool.toolParsers,
      contextWindow = contextWindow,
      keepAlive = keepAlive,
      think = think,
      cancellationToken = cancellationToken
    )
    val chunks = Promise[
      (
          collected: Result[RawAgentResponse, String],
          scalaCommands: Map[Int, Promise[ScalaToolResult]]
      )
    ]()
    val chunker = new ChunkOutput.ChunkReader[Chunk[(universe: String, scala_code: String)]]() {
      private val startedAtNanos = System.nanoTime()
      private val lastStatusAtNanos = new java.util.concurrent.atomic.AtomicLong(0L)
      private val receivedChunks = new java.util.concurrent.atomic.AtomicInteger(0)
      private val receivedChars = new java.util.concurrent.atomic.AtomicLong(0L)
      private val outstandingToolPromises = new java.util.concurrent.atomic.AtomicInteger(0)
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

      private def estimatedTokens(chars: Long): Long =
        if chars == 0 then 0L else math.max(1L, (chars + 3L) / 4L)

      private def elapsedSeconds: Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000_000L).max(0L)

      private def updateStatus(label: String, force: Boolean = false): Unit =
        val now = System.nanoTime()
        val previous = lastStatusAtNanos.get()
        if !force && previous != 0L && now - previous < 250_000_000L then ()
        else if lastStatusAtNanos.compareAndSet(previous, now) || force then
          val chunks = receivedChunks.get()
          val tokens = estimatedTokens(receivedChars.get())
          val pendingTools = outstandingToolPromises.get()
          val toolStatus =
            if pendingTools == 0 then ""
            else s", $pendingTools tool promises pending"
          log.setStatus(
            Some(
              s"${Console.CYAN}[$label: $chunks chunks, ~$tokens tokens$toolStatus, ${elapsedSeconds}s]${Console.RESET}"
            )
          )

      private def recordChunk(label: String, chars: Int): Unit =
        val chunks = receivedChunks.incrementAndGet()
        receivedChars.addAndGet(chars.toLong.max(0L))
        updateStatus(label, force = chunks == 1)

      private def checkThinkingDone(): Unit =
        if thinkingDone.compareAndSet(false, true) then log.print("\n")

      updateStatus("waiting", force = true)

      override def onChunk(chunk: Chunk[(universe: String, scala_code: String)]): Unit =
        seenAny.set(true)
        var chunkId = -1
        chunk match {
          case Chunk.Content(content) =>
            recordChunk("receiving content", content.length)
            checkThinkingDone()
            if seenContent.compareAndSet(false, true) then log.print("-----\n")
            log.print(Console.BOLD + content + Console.RESET)
          case Chunk.Thinking(thinking) =>
            recordChunk("thinking", thinking.length)
            log.print(Console.CYAN + thinking + Console.RESET)
          case Chunk.Tools(tool_calls) =>
            recordChunk(
              "calling tool",
              tool_calls.map(call => call.arguments.scala_code.length).sum
            )
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
              outstandingToolPromises.incrementAndGet()
              updateStatus("tool promise started", force = true)
              promise.future.onComplete { _ =>
                outstandingToolPromises.decrementAndGet()
                updateStatus("tool promise completed", force = true)
              }
              val result =
                availableSkills.find(_.universe == scalaCode.arguments.universe) match
                  case Some(skill) =>
                    ReplExec
                      .runCodeHarness(
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
              promise.tryCompleteWith(result)
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

      override def onComplete(
          finalState: ChunkOutputState & ChunkFinalState,
          usage: OllamaClient.Usage
      ): Unit =
        updateStatus("complete", force = true)
        if seenAny.get() then log.print("\n")
        chunks.success(
          finalState match
            case ChunkOutputState.Done =>
              (
                Result.Ok(RawAgentResponse(collected.asScala.toVector, usage)),
                scalaCommands.asScala.toMap
              )
            case ChunkOutputState.Error(msg) =>
              (Result.Err(msg), scalaCommands.asScala.toMap)
        )
    }
    request.send(chunker).flatMap {
      case Result.Ok(_) =>
        for
          cs <- chunks.future
          results <- Future.sequence(cs.scalaCommands.map((k, p) => p.future.map(r => (k, r))))
        yield cs.collected.map({ response =>
          AgentResponse(
            response.chunks.map({ (id, chunk) =>
              chunk match
                case Chunk.Content(content)   => ResolvedChunk.Content(content)
                case Chunk.Thinking(thinking) => ResolvedChunk.Thinking(thinking)
                case Chunk.Tools(tool_calls)  =>
                  ResolvedChunk.Tools(
                    tool_calls.zipWithIndex.map { case (tc, idx) =>
                      (
                        tc,
                        results.toMap
                          .getOrElse(id + idx, ScalaToolResult.Failure("No response"))
                      )
                    }.toVector
                  )
            }),
            response.usage
          )
        })
      case err: Result.Err[?] => Future.successful(err)
    }
  }

  def hasToolCalls(chunks: Seq[ScalaResolvedChunk]): Boolean =
    chunks.exists {
      case ResolvedChunk.Tools(toolCalls) => toolCalls.nonEmpty
      case _                              => false
    }

  def appendCollectedChunks(
      history: Vector[OllamaClient.ChatMessage],
      chunks: Seq[ScalaResolvedChunk]
  ): Vector[OllamaClient.ChatMessage] =
    history ++ messagesFromChunks(chunks)

  private def messagesFromChunks(
      chunks: Seq[ScalaResolvedChunk]
  ): Vector[OllamaClient.ChatMessage] = {
    val thinking = new StringBuilder
    val content = new StringBuilder
    val toolCalls = Vector.newBuilder[OllamaClient.ToolCall[ScalaToolCall]]
    val toolResults = Vector.newBuilder[OllamaClient.ChatMessage]

    chunks.foreach {
      case ResolvedChunk.Thinking(chunk) =>
        thinking.append(chunk)
      case ResolvedChunk.Content(chunk) =>
        content.append(chunk)
      case ResolvedChunk.Tools(calls) =>
        calls.foreach { case (toolCall, result) =>
          toolCalls += toolCall
          toolResults += OllamaClient.ChatMessage.tool(toolCall.name, result.encodeAsJson)
        }
    }

    val accumulatedToolCalls = toolCalls.result()
    val assistant =
      if thinking.nonEmpty || content.nonEmpty || accumulatedToolCalls.nonEmpty then
        Vector(
          OllamaClient.ChatMessage.assistant(
            thinking = thinking.toString,
            content = content.toString,
            toolCalls = accumulatedToolCalls
          )
        )
      else Vector.empty

    assistant ++ toolResults.result()
  }

}
