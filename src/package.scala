package capybara

package object agent:

  def main(query: Option[String]) = runAgent(query)

  @main def run(args: String*): Unit =
    val _ =
      if args.headOption.contains("skill-run") then
        mainargs.ParserForMethods(SkillRunner).runOrExit(args.tail)
      else if args.headOption.contains("skill-ide") then
        mainargs.ParserForMethods(SkillIde).runOrExit(args.tail)
      else mainargs.ParserForMethods(capybara.agent).runOrExit(args)
