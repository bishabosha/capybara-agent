package capybara

package object agent:

  def main(
      query: Option[String],
      @mainargs.arg(doc = "Directory of trusted Scala harness plugins (interactive mode)")
      pluginDir: Option[String] = None
  ) =
    require(query.isEmpty || pluginDir.isEmpty, "--plugin-dir is supported in interactive mode only")
    runAgent(query, pluginDir)

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
