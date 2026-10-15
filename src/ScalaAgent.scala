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

  case class ScalaToolCall(universe: String = "basic", scala_code: String, universes: Seq[String] = Nil) derives ReadWriter:
    def requestedUniverses: Seq[String] =
      if universes.nonEmpty then universes else universe.split(",").map(_.trim).filter(_.nonEmpty).toSeq
  type ScalaResolvedChunk = ResolvedChunk[ScalaToolCall, ScalaToolResult]
  final case class AgentResponse(chunks: Seq[ScalaResolvedChunk], usage: OllamaClient.Usage,
      completesToolLoop: Boolean = false)
  private type RawScalaChunk = (Int, Chunk[ScalaToolCall])
  private final case class RawAgentResponse(chunks: Seq[RawScalaChunk], usage: OllamaClient.Usage)

  private val skillsDir = pwd / "skills"
  private val builtinSkillsDir = pwd / "builtin-skills"
  val Model = "qwen3.8:27b-mlx"
  val DefaultContextWindow = 32768
  val DefaultKeepAlive = "30m"
  private val ContextWindowEnv = "CAPYBARA_CONTEXT_WINDOW"
  private val KeepAliveEnv = "CAPYBARA_KEEP_ALIVE"

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
        |Each skill expects its own calling convention.
        |For a composed call, supply universes = ["coding", "github"]. The legacy universe field still selects one skill.
        |Declared requires dependencies are loaded automatically, with their safe APIs and separate capabilities.
        |Skills remain atomic; composing them does not permit retaining capabilities in a pure callback.
        |
        |### Structured Output (available in every universe):
        |${builtinSkill.api}
        |The pure builtins API, puts/putsErr helpers, and formatting conversions are imported.
        |An OutputStreams capability is supplied as an explicit contextual parameter to your function.
        |Example: `puts(List(1, 2, 3).sum)`.
        |Do not use global `println`. Uncaught exceptions are caught and reported by the harness.
        |Tool results preserve separate stdout and stderr arrays. puts writes stdout; putsErr writes stderr.
        |Writing stderr alone is not execution failure. An executionError reports an uncaught exception or harness failure.
        |A successful call with no output confirms execution only; it supplies no computed answer.
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
        |Call `puts(value)` explicitly to emit structured output; it is available in every universe.
        |The final expression is discarded. Without a print call, successful execution produces no output.
        |Multiple print calls emit multiple values in order. Uncaught exceptions are reported automatically.
        |""".stripMargin
    )
    val request =
      try ModelProvider.request(
        messages = OllamaClient.ChatMessage.system(SystemPrompt) +: history,
        tools = tool.tools,
        toolParsers = tool.toolParsers,
        contextWindow = contextWindow,
        keepAlive = keepAlive,
        think = think,
        cancellationToken = cancellationToken,
        onStatus = text => log.setStatus(Some(text)),
        onNotice = text => log.styled(UiRole.Muted, s"[$text]\n")
      )
      catch
        case scala.util.control.NonFatal(error) =>
          return Future.successful(Result.Err(error.getMessage))
    val chunks = Promise[
      (
          collected: Result[RawAgentResponse, String],
          scalaCommands: Map[Int, Promise[ScalaToolResult]]
      )
    ]()
    val chunker = new ChunkOutput.ChunkReader[Chunk[ScalaToolCall]]() {
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
                ScalaToolCall
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
      private var seenThinking =
        new java.util.concurrent.atomic.AtomicBoolean(false)

      private def estimatedTokens(chars: Long): Long =
        if chars == 0 then 0L else math.max(1L, (chars + 3L) / 4L)

      private def elapsedSeconds: Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000_000L).max(0L)

      private def updateStatus(label: String, force: Boolean = false): Unit =
        if cancellationToken.isCancelled then ()
        else
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
            val heapStatus = RuntimeStatus.heapStatus
            log.setStatus(
              Some(
                s"[$label: $chunks chunks, ~$tokens tokens$toolStatus, ${elapsedSeconds}s, $heapStatus]"
              )
            )

      private def recordChunk(label: String, chars: Int): Unit =
        val chunks = receivedChunks.incrementAndGet()
        receivedChars.addAndGet(chars.toLong.max(0L))
        updateStatus(label, force = chunks == 1)

      private def checkThinkingDone(): Unit =
        if thinkingDone.compareAndSet(false, true) && seenThinking.get() then
          log.styled(UiRole.Thinking, "\n")

      updateStatus("waiting", force = true)

      override def onChunk(chunk: Chunk[ScalaToolCall]): Unit =
        var chunkId = -1
        chunk match {
          case Chunk.ResponseItems(_) | Chunk.ProviderState(_, _) => ()
          case Chunk.Content(content) if content.nonEmpty =>
            recordChunk("receiving content", content.length)
            checkThinkingDone()
            if seenContent.compareAndSet(false, true) then log.separator()
            log.styled(UiRole.Assistant, content)
          case Chunk.Content(_) => ()
          case Chunk.Thinking(thinking) =>
            seenThinking.set(true)
            recordChunk("thinking", thinking.length)
            log.styled(UiRole.Thinking, thinking)
          case Chunk.Tools(tool_calls) =>
            recordChunk(
              "calling tool",
              tool_calls.map(call => call.arguments.scala_code.length).sum
            )
            checkThinkingDone()
            if seenTools.compareAndSet(false, true) then log.separator()
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
                Skills.resolve(scalaCode.arguments.requestedUniverses, availableSkills, builtinSkill) match
                  case Result.Ok(skills) =>
                    ReplExec
                      .runCodeHarness(
                        builtinSkill,
                        skills,
                        scalaCode.arguments.scala_code,
                        callId,
                        log,
                        cancellationToken
                      )
                  case Result.Err(error) =>
                    val id = ToolExecutionHistory.nextId()
                    log.executionStarted(id, scalaCode.arguments.requestedUniverses, scalaCode.arguments.scala_code)
                    log.executionOutput(id, ToolOutputChannel.Stderr, error + "\n")
                    log.executionFinished(id, ToolExecutionState.Failed)
                    Future.successful(ScalaToolResult.Failure(error))
              promise.tryCompleteWith(result)
              scalaCode.resultSink.foreach { sink =>
                result.onComplete {
                  case scala.util.Success(value) => sink(value.encodeAsJson)
                  case scala.util.Failure(error) => sink(ScalaToolResult.Failure(error.toString).encodeAsJson)
                }
              }
        }
        collected.add((chunkId, chunk))

      override def onComplete(
          finalState: ChunkOutputState & ChunkFinalState,
          usage: OllamaClient.Usage
      ): Unit =
        updateStatus("complete", force = true)
        if seenContent.get() then log.styled(UiRole.Assistant, "\n")
        else checkThinkingDone()
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
                case Chunk.ResponseItems(items) => ResolvedChunk.ResponseItems(items)
                case Chunk.ProviderState(provider, state) => ResolvedChunk.ProviderState(provider, state)
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
            response.usage,
            completesToolLoop = request.completesToolLoop
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
    var responseItems: Option[Vector[ujson.Value]] = None
    var providerState = Map.empty[String, ujson.Value]

    chunks.foreach {
      case ResolvedChunk.ResponseItems(items) =>
        responseItems = Some(items)
      case ResolvedChunk.ProviderState(provider, state) =>
        providerState += provider -> state
      case ResolvedChunk.Thinking(chunk) =>
        thinking.append(chunk)
      case ResolvedChunk.Content(chunk) =>
        content.append(chunk)
      case ResolvedChunk.Tools(calls) =>
        calls.foreach { case (toolCall, result) =>
          toolCalls += toolCall
          toolResults += OllamaClient.ChatMessage.tool(toolCall.name, result.encodeAsJson, toolCall.id)
        }
    }

    val accumulatedToolCalls = toolCalls.result()
    val assistant =
      if thinking.nonEmpty || content.nonEmpty || accumulatedToolCalls.nonEmpty || responseItems.nonEmpty || providerState.nonEmpty then
        Vector(
          OllamaClient.ChatMessage.assistant(
            thinking = thinking.toString,
            content = content.toString,
            toolCalls = accumulatedToolCalls
          ).copy(responseItems = responseItems, providerState = providerState)
        )
      else Vector.empty

    assistant ++ toolResults.result()
  }

}
