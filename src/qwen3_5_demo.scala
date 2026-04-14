//> using toolkit 0.7.0
package capybara.agent

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

def main(query: Option[String]) =
  query match
    case Some(q) =>
      awaitAll(
        Qwen3_5.singleRequest(q, Logger.ConsoleLogger)
      )
    case None =>
      Terminal.run(Qwen3_5.singleRequest(_, summon[Logger]))

@main def run(args: String*): Unit =
  val _ = mainargs.ParserForMethods(this).runOrExit(args)

private def awaitAll[T](f: Future[T]): T =
  Await.result(f, scala.concurrent.duration.Duration.Inf)
