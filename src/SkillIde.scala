package capybara.agent

import steps.result.Result
import steps.result.Result.eval.ok
import steps.result.Result.apply as result

/** Set up BSP directly from a skill's sources and Scala CLI directives. No build files. */
object SkillIde:
  @mainargs.main(doc = "Prepare an existing skill directory for Metals or IntelliJ using Scala CLI.")
  def main(@mainargs.arg(doc = "Skill directory") skill: String): Unit =
    prepare(skill) match
      case Result.Ok(directory) =>
        println(s"Open $directory in Metals or import it as a BSP project in IntelliJ.")
        println(s"Capability wiring: ${directory / ".agent-runtime" / "SkillPreview.scala"}")
      case Result.Err(error) =>
        scala.Console.err.println(error)
        sys.exit(1)

  def prepare(skillDirectory: String): Result[os.Path, String] =
    Result.catchException { case error => error.toString } {
      result {
        val directory = os.Path(skillDirectory, os.pwd)
        val skill = Skills.loadFrom(directory).ok
        val builtinsDirectory = os.pwd / "builtin-skills" / "capybara-builtins"
        val builtins = Skills.loadFrom(builtinsDirectory).ok
        val preview = directory / ".agent-runtime" / "SkillPreview.scala"
        // The builtin skill is already present when developing that skill itself.
        val baseline =
          if directory == builtinsDirectory then Nil
          else Seq(
            (builtinsDirectory / "scripts" / "Interface.scala").toString,
            (builtinsDirectory / "scripts" / "Implementation.scala").toString
          )
        val selected = if directory == builtinsDirectory then Skills.Basic else skill
        val resolved = ReplExec.prepareSkills(builtins, Seq(selected)).ok
        val dependencyJars = resolved.filterNot(_.universe == selected.universe).flatMap(s =>
          Seq("--extra-jar", (os.pwd / ".agent-runtime/interface" / s.universe / "impl.jar").toString))
        os.write.over(preview, SkillScaffold.preview(builtins, resolved), createFolders = true)
        val _ = os.call(
          cmd = Seq("scala", "setup-ide", directory.toString, preview.toString) ++ baseline ++
            dependencyJars ++ Seq("--workspace", directory.toString, "--scala", ReplExec.ScalaVersion, "--scalac-option=-preview"),
          cwd = directory
        )
        directory
      }
    }.flatMap(identity)
