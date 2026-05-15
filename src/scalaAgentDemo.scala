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
  query match
    case Some(q) =>
      given Logger = Logger.ConsoleLogger
      awaitAll(
        runSession(q, Vector.empty, usageTracker, contextWindow, keepAlive, CancellationToken.Never)
          .map(printErrorIfNeeded)
      )
    case None =>
      val chatHistory =
        AtomicReference[Vector[OllamaClient.ChatMessage]](Vector.empty)
      val activeRun = AtomicLong(0)
      val activeToken =
        AtomicReference[CancellationToken | Null](null)
      Terminal.run { query =>
        val logger = summon[Logger]
        query.trim match
          case "/usage" =>
            logger.print(usageTracker.render)
          case _ =>
            val previous = activeToken.getAndSet(null)
            if previous != null then
              previous.cancel()
              logger.print("\n[interrupted]\n")

            val runId = activeRun.incrementAndGet()
            val token = CancellationToken()
            activeToken.set(token)
            val startingHistory = chatHistory.get()
            printThinking()(using logger)

            runSession(query, startingHistory, usageTracker, contextWindow, keepAlive, token)
              .foreach { result =>
                val isCurrent = activeRun.get() == runId && !token.isCancelled
                if isCurrent then
                  activeToken.compareAndSet(token, null)
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
    usageTracker: UsageTracker,
    contextWindow: Int,
    keepAlive: String,
    cancellationToken: CancellationToken
)(using
    Logger
): Future[Result[Vector[OllamaClient.ChatMessage], String]] =
  continueSession(
    history :+ OllamaClient.ChatMessage.user(query),
    0,
    usageTracker,
    contextWindow,
    keepAlive,
    cancellationToken
  )

private def continueSession(
    history: Vector[OllamaClient.ChatMessage],
    requestCount: Int,
    usageTracker: UsageTracker,
    contextWindow: Int,
    keepAlive: String,
    cancellationToken: CancellationToken
)(using Logger): Future[Result[Vector[OllamaClient.ChatMessage], String]] =
  if cancellationToken.isCancelled then Future.successful(Result.Err("cancelled"))
  else if requestCount >= MaxAgentRequests then
    Future.successful(Result.Err(s"agent loop exceeded $MaxAgentRequests model requests"))
  else
    ScalaAgent
      .singleRequest(history, summon[Logger], contextWindow, keepAlive, cancellationToken)
      .flatMap {
        case Result.Ok(response) =>
          usageTracker.record(response.usage)
          val updatedHistory = ScalaAgent.appendCollectedChunks(history, response.chunks)
          if ScalaAgent.hasToolCalls(response.chunks) then
            continueSession(
              updatedHistory,
              requestCount + 1,
              usageTracker,
              contextWindow,
              keepAlive,
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

private def printThinking()(using Logger): Unit =
  summon[Logger].setStatus(Some(s"${Console.CYAN}[thinking...]${Console.RESET}"))

@main def run(args: String*): Unit =
  val _ = mainargs.ParserForMethods(this).runOrExit(args)

private def awaitAll[T](f: Future[T]): T =
  Await.result(f, scala.concurrent.duration.Duration.Inf)
