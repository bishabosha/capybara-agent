package capybara

package object agent:

  def main(query: Option[String]) = runAgent(query)

  @main def run(args: String*): Unit =
    val _ =
      if args.headOption.contains("skill-run") then
        mainargs.ParserForMethods(SkillRunner).runOrExit(args.tail)
      else if args.headOption.contains("skill-ide") then
        mainargs.ParserForMethods(SkillIde).runOrExit(args.tail)
      else if args.headOption.contains("skill-publish") then
        mainargs.ParserForMethods(SkillArtifacts).runOrExit(args.tail)
      else if args.headOption.contains("skill-transcript") then
        mainargs.ParserForMethods(SkillTranscript).runOrExit(args.tail)
      else if args.headOption.contains("workshop-setup") then
        mainargs.ParserForMethods(WorkshopSetup).runOrExit(args.tail)
      else if args.headOption.contains("coding-demo") then
        mainargs.ParserForMethods(CodingDemo).runOrExit(args.tail)
      else mainargs.ParserForMethods(capybara.agent).runOrExit(args)
