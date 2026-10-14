package capybara

package object agent:

  def main(query: Option[String]) = runAgent(query)

  @main def run(args: String*): Unit =
    val _ = mainargs.ParserForMethods(capybara.agent).runOrExit(args)
