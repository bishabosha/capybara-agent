package capybara.agent

import capybara.agent.OllamaClient.Chunk
import capybara.agent.OllamaClient.ChunkFinalState
import capybara.agent.OllamaClient.ChunkOutputState
import os.pwd
import steps.result.Result
import upickle.default.*
import upickle.jsonschema.*

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.given

import OllamaClient.ChunkOutput
import capybara.agent.OllamaClient.ResolvedChunk
import ReplExec.ScalaToolResult

object ScalaAgent {

  sealed trait ToolArguments

  case class ScalaToolCall(universe: String = "basic", scala_code: String, universes: Seq[String] = Nil) extends ToolArguments:
    def requestedUniverses: Seq[String] =
      if universes.nonEmpty then universes else universe.split(",").map(_.trim).filter(_.nonEmpty).toSeq
  case class SkillNames(universes: Seq[String] = Nil) extends ToolArguments

  // Concrete children of a sealed trait also get tagged codecs from `derives`.
  // Tool arguments are ordinary JSON objects: the tool name selects their type.
  object ScalaToolCall:
    given ReadWriter[ScalaToolCall] = readwriter[ujson.Value].bimap[ScalaToolCall](
      call => ujson.Obj(
        "universe" -> call.universe,
        "scala_code" -> call.scala_code,
        "universes" -> writeJs(call.universes)
      ),
      value => ScalaToolCall(
        universe = value.obj.get("universe").map(_.str).getOrElse("basic"),
        scala_code = value("scala_code").str,
        universes = value.obj.get("universes").map(_.arr.iterator.map(_.str).toVector).getOrElse(Vector.empty)
      )
    )

  object SkillNames:
    given ReadWriter[SkillNames] = readwriter[ujson.Value].bimap[SkillNames](
      names => ujson.Obj("universes" -> writeJs(names.universes)),
      value => SkillNames(value.obj.get("universes").map(_.arr.iterator.map(_.str).toVector).getOrElse(Vector.empty))
    )

  object ToolArguments:
    // Keep wire arguments free of sealed-trait discriminator fields.
    given ReadWriter[ToolArguments] = readwriter[ujson.Value].bimap[ToolArguments](
      {
        case call: ScalaToolCall => writeJs(call)
        case names: SkillNames => writeJs(names)
      },
      value => if value.obj.contains("scala_code") then read[ScalaToolCall](value) else read[SkillNames](value)
    )

  private def executionParser: ReadWriter[ToolArguments] =
    readwriter[ScalaToolCall].bimap[ToolArguments](
      { case call: ScalaToolCall => call; case _ => throw IllegalArgumentException("Expected Scala code") },
      call => call
    )

  private def discoveryParser: ReadWriter[ToolArguments] =
    readwriter[SkillNames].bimap[ToolArguments](
      { case names: SkillNames => names; case _ => throw IllegalArgumentException("Expected skill names") },
      names => names
    )

  type ScalaResolvedChunk = ResolvedChunk[ToolArguments, ScalaToolResult]
  final case class AgentResponse(chunks: Seq[ScalaResolvedChunk], usage: OllamaClient.Usage,
      completesToolLoop: Boolean = false)
  private type RawScalaChunk = (Int, Chunk[ToolArguments])
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
  val SystemPrompt: String = SkillDiscovery.systemPrompt(availableSkills)

  // The native Apple bridge currently exposes exactly one execution tool.
  private lazy val NativeSystemPrompt = SystemPrompt
    .replace(SkillDiscovery.DiscoveryInstructions, "Skill APIs and calling instructions follow.") +
    "\n\n" + renderSkills(availableSkills)

  def configuredSystemPrompt: String =
    if sys.env.get("CAPYBARA_PROVIDER").exists(_.trim == "apple-fm") then NativeSystemPrompt else SystemPrompt

  private[agent] def toolDefinitions(native: Boolean = false):
      (tools: Vector[OllamaClient.ToolDefinition], toolParsers: Map[String, ReadWriter[ToolArguments]]) =
    val execution = OllamaClient.toolsDef[ScalaToolCall]("run_scala_code",
      "Execute a Scala snippet body. Use basic for standard-library computation. " +
      "For other universes, read their skill instructions first. Call supplied capabilities directly; " +
      "do not implement their interfaces or create capability instances. " +
      "Use puts(value) for output; the final expression is discarded. Do not announce tool calls.")
    val describe = OllamaClient.toolsDef[SkillNames]("describe_skills",
      "Read short descriptions of named skill universes, without loading APIs. Empty universes lists all descriptions.")
    val readSkill = OllamaClient.toolsDef[SkillNames]("read_skills",
      "Read calling instructions and supplied capability names for named universes and their dependencies. " +
      "Requires at least one universe. This only reads documentation; it does not execute code.")
    (
      tools = if native then execution.tools else execution.tools ++ describe.tools ++ readSkill.tools,
      toolParsers = if native then Map("run_scala_code" -> executionParser)
        else Map("run_scala_code" -> executionParser, "describe_skills" -> discoveryParser,
          "read_skills" -> discoveryParser)
    )

  private[agent] def discoveryResult(name: String, names: SkillNames,
      available: Seq[Skill] = availableSkills, builtins: Skill = builtinSkill): ScalaToolResult =
    val result = name match
      case "describe_skills" => SkillDiscovery.describe(names.universes, available)
      case "read_skills" => SkillDiscovery.read(names.universes, available, builtins)
      case other => Result.Err(s"Unknown discovery tool: $other")
    result match
      case Result.Ok(text) => ScalaToolResult.Success(Vector(ujson.Str(text)), Vector.empty)
      case Result.Err(error) => ScalaToolResult.Failure(error)

  private[agent] def runDiscovery(name: String, names: SkillNames, log: Logger): ScalaToolResult =
    val id = ToolExecutionHistory.nextId()
    log.toolStarted(id, name, names.universes, writeJs(names).render(indent = 2))
    log.executionRunning(id)
    val result = discoveryResult(name, names)
    def output(channel: ToolOutputChannel, value: ujson.Value): Unit =
      val text = value match
        case ujson.Str(text) => text
        case other => other.render(indent = 2)
      log.executionOutput(id, channel, text + "\n")
    result match
      case ScalaToolResult.Success(stdout, stderr) =>
        stdout.foreach(output(ToolOutputChannel.Stdout, _))
        stderr.foreach(output(ToolOutputChannel.Stderr, _))
        log.executionFinished(id, ToolExecutionState.Succeeded)
      case ScalaToolResult.Failure(error, stdout, stderr) =>
        stdout.foreach(output(ToolOutputChannel.Stdout, _))
        stderr.foreach(output(ToolOutputChannel.Stderr, _))
        output(ToolOutputChannel.Stderr, error)
        log.executionFinished(id, ToolExecutionState.Failed)
      case ScalaToolResult.NoOutput => log.executionFinished(id, ToolExecutionState.Succeeded)
    result

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
    val native = sys.env.get("CAPYBARA_PROVIDER").exists(_.trim == "apple-fm")
    val tool = toolDefinitions(native)
    val request =
      try ModelProvider.request(
        messages = OllamaClient.ChatMessage.system(configuredSystemPrompt) +: history,
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
    val chunker = new ChunkOutput.ChunkReader[Chunk[ToolArguments]]() {
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
                ToolArguments
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

      override def onChunk(chunk: Chunk[ToolArguments]): Unit =
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
              tool_calls.map(call => writeJs(call.arguments).render().length).sum
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
              val result = scalaCode.arguments match
                case names: SkillNames =>
                  Future.successful(runDiscovery(scalaCode.name, names, log))
                case call: ScalaToolCall =>
                  Skills.resolve(call.requestedUniverses, availableSkills, builtinSkill) match
                    case Result.Ok(skills) =>
                      ReplExec.runCodeHarness(builtinSkill, skills, call.scala_code, callId, log, cancellationToken)
                    case Result.Err(error) =>
                      val id = ToolExecutionHistory.nextId()
                      log.executionStarted(id, call.requestedUniverses, call.scala_code)
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
    val toolCalls = Vector.newBuilder[OllamaClient.ToolCall[ToolArguments]]
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
