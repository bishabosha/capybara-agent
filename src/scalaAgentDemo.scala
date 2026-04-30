//> using toolkit 0.7.0
package capybara.agent

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import steps.result.Result

private val MaxAgentRequests = 16

def main(query: Option[String]) =
  query match
    case Some(q) =>
      given Logger = Logger.ConsoleLogger
      awaitAll(runSession(q, Vector.empty).map(printErrorIfNeeded))
    case None =>
      var chatHistory = Vector.empty[OllamaClient.ChatMessage]
      Terminal.run { query =>
        awaitAll(runSession(query, chatHistory).map { result =>
          result match
            case Result.Ok(updatedHistory) =>
              chatHistory = updatedHistory
            case Result.Err(error) =>
              printError(error)
        })
        Terminal.Continue
      }

def runSession(query: String, history: Vector[OllamaClient.ChatMessage])(using
    Logger
): Future[Result[Vector[OllamaClient.ChatMessage], String]] =
  continueSession(history :+ OllamaClient.ChatMessage.user(query), 0)

private def continueSession(
    history: Vector[OllamaClient.ChatMessage],
    requestCount: Int
)(using Logger): Future[Result[Vector[OllamaClient.ChatMessage], String]] =
  if requestCount >= MaxAgentRequests then
    Future.successful(Result.Err(s"agent loop exceeded $MaxAgentRequests model requests"))
  else
    ScalaAgent.singleRequest(history, summon[Logger]).flatMap {
      case Result.Ok(chunks) =>
        val updatedHistory = ScalaAgent.appendCollectedChunks(history, chunks)
        if ScalaAgent.hasToolCalls(chunks) then continueSession(updatedHistory, requestCount + 1)
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

@main def run(args: String*): Unit =
  val _ = mainargs.ParserForMethods(this).runOrExit(args)

private def awaitAll[T](f: Future[T]): T =
  Await.result(f, scala.concurrent.duration.Duration.Inf)
