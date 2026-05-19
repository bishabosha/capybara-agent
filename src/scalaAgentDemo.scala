//> using toolkit 0.7.0
package capybara.agent

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import steps.result.Result
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val MaxAgentRequests = 16

def main(query: Option[String]) =
  val contextWindow = ScalaAgent.configuredContextWindow
  val keepAlive = ScalaAgent.configuredKeepAlive
  val usageTracker = UsageTracker(Some(contextWindow))
  val defaultThinkingMode = ThinkingInference.ThinkingMode.Auto
  query match
    case Some(q) =>
      given Logger = Logger.ConsoleLogger
      val agentSession = ScalaAgent.AgentSession.create()
      awaitAll(
        runSession(
          q,
          Vector.empty,
          agentSession,
          usageTracker,
          contextWindow,
          keepAlive,
          defaultThinkingMode,
          CancellationToken.Never
        )
          .map(printErrorIfNeeded)
      )
    case None =>
      val agentSession = ScalaAgent.AgentSession.create()
      val chatHistory =
        AtomicReference[Vector[OllamaClient.ChatMessage]](Vector.empty)
      val activeRun = AtomicLong(0)
      val activeToken =
        AtomicReference[CancellationToken | Null](null)
      val thinkingMode =
        AtomicReference[ThinkingInference.ThinkingMode](defaultThinkingMode)
      Terminal.run { query =>
        val logger = summon[Logger]
        query.trim match
          case "/x" =>
            val previous = activeToken.getAndSet(null)
            if previous != null then previous.cancel()
            val replInterrupted = ReplExec.interruptActiveExecution()
            if previous != null || replInterrupted then
              logger.print("\n[interrupt requested]\n")
              printReadyForNextTurn()(using logger)
            else logger.print("[nothing to interrupt]\n")
          case "/usage" =>
            logger.print(usageTracker.render)
          case "/sys-prompt" =>
            logger.print(s"${ScalaAgent.systemPrompt(agentSession)}\n")
          case "/think" =>
            logger.print(s"thinking mode: ${thinkingMode.get().displayName}\n")
          case command if command.startsWith("/think ") =>
            val rawMode = command.stripPrefix("/think").trim
            ThinkingInference.ThinkingMode.parse(rawMode) match
              case Some(mode) =>
                thinkingMode.set(mode)
                logger.print(s"thinking mode: ${mode.displayName}\n")
              case None =>
                logger.print("usage: /think on | /think off | /think auto\n")
          case _ =>
            val previous = activeToken.getAndSet(null)
            if previous != null then
              previous.cancel()
              val _ = ReplExec.interruptActiveExecution()
              logger.print("\n[interrupted]\n")

            val runId = activeRun.incrementAndGet()
            val token = CancellationToken()
            activeToken.set(token)
            val startingHistory = chatHistory.get()
            val mode = thinkingMode.get()
            val think = ThinkingInference.shouldThink(query, mode)
            printThinking(mode, think)(using logger)

            runSession(
              query,
              startingHistory,
              agentSession,
              usageTracker,
              contextWindow,
              keepAlive,
              mode,
              token
            )
              .foreach { result =>
                val isCurrent = activeRun.get() == runId && !token.isCancelled
                if isCurrent then
                  val _ = activeToken.compareAndSet(token, null)
                  result match
                    case Result.Ok(updatedHistory) =>
                      chatHistory.set(updatedHistory)
                    case Result.Err("cancelled") =>
                      ()
                    case Result.Err(error) =>
                      printError(error)(using logger)
                  printReadyForNextTurn()(using logger)
                else ()
              }
        Terminal.NextState.Continue
      }

def runSession(
    query: String,
    history: Vector[OllamaClient.ChatMessage],
    agentSession: ScalaAgent.AgentSession,
    usageTracker: UsageTracker,
    contextWindow: Int,
    keepAlive: String,
    thinkingMode: ThinkingInference.ThinkingMode,
    cancellationToken: CancellationToken
)(using
    Logger
): Future[Result[Vector[OllamaClient.ChatMessage], String]] =
  val think = ThinkingInference.shouldThink(query, thinkingMode)
  continueSession(
    history :+ OllamaClient.ChatMessage.user(query),
    agentSession,
    0,
    usageTracker,
    contextWindow,
    keepAlive,
    think,
    cancellationToken
  )

private def continueSession(
    history: Vector[OllamaClient.ChatMessage],
    agentSession: ScalaAgent.AgentSession,
    requestCount: Int,
    usageTracker: UsageTracker,
    contextWindow: Int,
    keepAlive: String,
    think: Boolean,
    cancellationToken: CancellationToken
)(using Logger): Future[Result[Vector[OllamaClient.ChatMessage], String]] =
  if cancellationToken.isCancelled then Future.successful(Result.Err("cancelled"))
  else if requestCount >= MaxAgentRequests then
    Future.successful(Result.Err(s"agent loop exceeded $MaxAgentRequests model requests"))
  else
    ScalaAgent
      .singleRequest(
        history,
        agentSession,
        summon[Logger],
        contextWindow,
        keepAlive,
        think,
        cancellationToken
      )
      .flatMap {
        case Result.Ok(response) =>
          usageTracker.record(response.usage)
          val updatedHistory = ScalaAgent.appendCollectedChunks(history, response.chunks)
          if ScalaAgent.hasToolCalls(response.chunks) then
            continueSession(
              updatedHistory,
              agentSession,
              requestCount + 1,
              usageTracker,
              contextWindow,
              keepAlive,
              think,
              cancellationToken
            )
          else Future.successful(Result.Ok(updatedHistory))
        case Result.Err(error) =>
          Future.successful(Result.Err(error))
      }

private def printErrorIfNeeded(result: Result[?, String])(using Logger): Unit =
  result match
    case Result.Ok(_)      => ()
    case Result.Err(error) => printError(error)

private def printError(error: String)(using Logger): Unit =
  error.linesIterator
    .map(line => s"${Console.RED}[error] $line${Console.RESET}\n")
    .foreach(summon[Logger].print)

private def printReadyForNextTurn()(using Logger): Unit =
  summon[Logger].setStatus(None)
  summon[Logger].print(s"${Console.GREEN}[your turn]${Console.RESET}\n")

private def printThinking(mode: ThinkingInference.ThinkingMode, think: Boolean)(using
    Logger
): Unit =
  val status =
    if think then s"[thinking enabled, mode=${mode.displayName}]"
    else s"[thinking disabled, mode=${mode.displayName}]"
  summon[Logger].setStatus(Some(s"${Console.CYAN}$status${Console.RESET}"))

@main def run(args: String*): Unit =
  val _ = mainargs.ParserForMethods(this).runOrExit(args)

private def awaitAll[T](f: Future[T]): T =
  Await.result(f, scala.concurrent.duration.Duration.Inf)
