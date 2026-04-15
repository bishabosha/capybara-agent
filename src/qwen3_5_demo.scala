//> using toolkit 0.7.0
package capybara.agent

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import steps.result.Result

def main(query: Option[String]) =
  query match
    case Some(q) =>
      given Logger = Logger.ConsoleLogger
      awaitAll(runSession(q))
    case None =>
      Terminal.run(runSession)

def runSession(query: String)(using Logger): Future[Unit] =
  for res <- Qwen3_5.singleRequest(query, summon[Logger]) yield {
    res match
      case Result.Ok(_)      => // ignore for now
      case Result.Err(error) =>
        error.linesIterator
          .map(line => s"${Console.RED}[error] $line${Console.RESET}\n")
          .foreach(summon[Logger].print)
  }

@main def run(args: String*): Unit =
  val _ = mainargs.ParserForMethods(this).runOrExit(args)

private[agent] def awaitAll[T](f: Future[T]): T =
  Await.result(f, scala.concurrent.duration.Duration.Inf)
