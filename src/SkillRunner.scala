package capybara.agent

import steps.result.Result
import steps.result.Result.eval.{ok, raise}
import steps.result.Result.apply as result

/** Direct developer entry point. Does not initialize ScalaAgent or a model provider. */
object SkillRunner:
  @mainargs.main(doc = "Execute a Scala snippet directly through ReplExec, without an LLM.")
  def main(
      @mainargs.arg(doc = "Skill directory, or basic for standard-library code") skill: String,
      @mainargs.arg(doc = "Path to a Scala snippet") file: String
  ): Unit =
    run(skill, file) match
      case Result.Ok(output) =>
        scala.Console.print(output.stdout)
        scala.Console.err.print(output.stderr)
        ReplExec.decodeOutput(output) match
          case ReplExec.ScalaToolResult.Failure(_, _, _) => sys.exit(1)
          case _ => ()
      case Result.Err(error) =>
        scala.Console.err.println(error)
        sys.exit(1)

  def run(skillDirectory: String, snippetFile: String): Result[ReplExec.CodeOutput, String] =
    Result.catchException { case error => error.toString } {
      result {
        val builtins = Skills.loadFrom(os.pwd / "builtin-skills" / "capybara-builtins").ok
        val skill =
          if skillDirectory == "basic" then Skills.Basic
          else Skills.loadFrom(os.Path(skillDirectory, os.pwd)).ok
        if skill.name != skill.universe then
          raise(s"Skill name '${skill.name}' must match universe '${skill.universe}'.")
        val code = Skills.readSafely(os.Path(snippetFile, os.pwd)).ok
        ReplExec.runCode(builtins, skill, code).ok
      }
    }.flatMap(identity)
