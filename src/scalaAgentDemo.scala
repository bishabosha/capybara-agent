//> using toolkit 0.7.0
package capybara.agent

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import steps.result.Result

private val MaxAgentRequests = 16

def runAgent(query: Option[String], pluginDir: Option[String] = None) =
  val contextWindow = ScalaAgent.configuredContextWindow
  val keepAlive = ScalaAgent.configuredKeepAlive
  val usageTracker = UsageTracker(Some(contextWindow))
  val defaultThinkingMode = ThinkingInference.ThinkingMode.Auto
  query match
    case Some(q) =>
      given Logger = Logger.ConsoleLogger
      awaitAll(
        runSession(
          q,
          Vector.empty,
          usageTracker,
          contextWindow,
          keepAlive,
          defaultThinkingMode,
          CancellationToken.Never
        )
          .map(printErrorIfNeeded)
      )
    case None =>
      val directory = pluginDir.map(path => os.Path(path, os.pwd))
      HarnessPlugins.load(directory) match
        case Left(error) => throw IllegalArgumentException(error)
        case Right(plugins) =>
          val session = InteractiveSession(
            plugins,
            () => HarnessPlugins.load(directory),
            () => usageTracker.render,
            () => ScalaAgent.SystemPrompt,
            (prompt, history, mode, token, logger) =>
              runSession(prompt, history, usageTracker, contextWindow, keepAlive, mode, token)(using logger)
          )
          try
            Terminal.run(
              query => session.accept(query),
              () => session.commandNames,
              session.open(),
              session.close()
            )
          finally session.close()(using Logger.ConsoleLogger)

def runSession(
    query: String,
    history: Vector[OllamaClient.ChatMessage],
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
    0,
    usageTracker,
    contextWindow,
    keepAlive,
    think,
    cancellationToken
  )

private def continueSession(
    history: Vector[OllamaClient.ChatMessage],
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
      .singleRequest(history, summon[Logger], contextWindow, keepAlive, think, cancellationToken)
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

private def awaitAll[T](f: Future[T]): T =
  Await.result(f, scala.concurrent.duration.Duration.Inf)
