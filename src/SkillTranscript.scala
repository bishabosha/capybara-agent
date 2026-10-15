package capybara.agent

import steps.result.Result

/** Render generated REPL commands without compiling or executing a model's snippet. */
object SkillTranscript:
  private val normalizedId = "00000000_0000_0000_0000_000000000001"

  def render(builtins: Skill, skill: Skill, code: String, root: os.Path = os.pwd): String =
    def load(selected: Skill): Seq[String] =
      if !selected.requiresRuntimeClasspath then Nil
      else
        val folder = root / ".agent-runtime" / "interface" / selected.universe
        Seq(s":jar ${folder / "sigs.jar"}", s":jar ${folder / "impl.jar"}") ++ selected.predef.toSeq
    val bindings = SkillScaffold.bindings(builtins, skill)
    val method = s"agentCode_$normalizedId"
    val entry = s"harnessEntry_$normalizedId"
    val commands = load(builtins) ++ (
      if skill.requiresRuntimeClasspath && skill.universe != builtins.universe then load(skill) else Nil
    ) ++ Seq(
      "// HOST: clear the language-feature setting before changing mode.",
      ":settings -language:experimental.safe",
      SkillScaffold.definition(method, bindings, code),
      "// HOST: clear the language-feature setting again.",
      ":settings -language:experimental.captureChecking",
      SkillScaffold.entryPoint(entry, method, bindings)
    )
    s"""// Generated command transcript for universe '${skill.universe}'.
       |// The per-call UUID is normalized; all methods use the actual scaffold templates.
       |// Initial ReplDriver language: experimental.captureChecking.
       |// Cache packaging, if needed, happens before these REPL commands.
       |
       |${commands.mkString("\n\n")}
       |// HOST invocation, not a submitted REPL command:
       |// invokeEntryPoint("$entry", stdout.stream, stderr.stream)
       |// This reflects the compiled entry method with two host-owned PrintStreams.
       |// Finally: resetToInitial(), restore initialState, and clear diagnostics.
       |""".stripMargin

  @mainargs.main(doc = "Print generated REPL commands for one tool call; do not execute it.")
  def main(
      @mainargs.arg(doc = "Skill directory, or basic") skill: String,
      @mainargs.arg(doc = "Snippet file") file: String
  ): Unit =
    def load(path: String): Skill = Skills.loadFrom(os.Path(path, os.pwd)) match
      case Result.Ok(value) => value
      case Result.Err(error) => throw IllegalArgumentException(error)
    val builtins = load("builtin-skills/capybara-builtins")
    val selected = if skill == "basic" then Skills.Basic else load(skill)
    scala.Console.print(render(builtins, selected, os.read(os.Path(file, os.pwd))))
