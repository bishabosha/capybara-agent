package capybara.agent

import steps.result.Result
import steps.result.Result.eval.ok
import steps.result.Result.apply as result

/** Only safe interface artifacts are published. Factories and credentials stay in implementation JARs. */
object SkillArtifacts:
  val Organization = "dev.capybara.skills"
  val Version = "0.1.0-SNAPSHOT"

  def coordinate(skill: Skill): String = s"$Organization::${skill.universe}-api:$Version"

  def dependencies(sources: String*): Seq[String] =
    val directive = "(?m)^\\s*//>\\s+using\\s+(?:dep|deps|dependencies)\\s+([^\\n]+)".r
    val localApi = (java.util.regex.Pattern.quote(Organization) +
      "(?:::([A-Za-z0-9_-]+)-api:|:([A-Za-z0-9_-]+)-api_3:)([^\\s\\\"']+)").r
    sources.flatMap(source => directive.findAllMatchIn(source).flatMap { line =>
      localApi.findAllMatchIn(line.group(1)).map { artifact =>
        require(artifact.group(3) == Version, s"Local skill API version must be $Version")
        Option(artifact.group(1)).getOrElse(artifact.group(2))
      }
    }).distinct

  def publish(skill: Skill, source: os.Path): Unit =
    val result = os.proc("scala", "--power", "publish", "local", source.toString,
      "-S", ReplExec.ScalaVersion, "-language:experimental.safe", "--organization", Organization,
      "--name", s"${skill.universe}-api", "--project-version", Version,
      "--sources-jar=false", "--doc=false", "--signer", "none")
      .call(check = false, timeout = 120000, stdout = os.Pipe, stderr = os.Pipe)
    require(result.exitCode == 0, s"Could not publish safe API ${coordinate(skill)}:\n" +
      result.out.text() + result.err.text())

  @mainargs.main(doc = "Publish checked skill APIs to local Ivy, with dependencies first; never invoke factories.")
  def main(@mainargs.arg(doc = "Skill name or directory; comma-separated selections are supported") skill: String): Unit =
    publishLocal(skill) match
      case Result.Ok(coordinates) =>
        println("Published safe APIs to local Ivy (implementations remain separate).")
        println("//> using repository ivy2local")
        coordinates.foreach(coordinate => println(s"//> using dep \"$coordinate\""))
      case Result.Err(error) =>
        scala.Console.err.println(error)
        sys.exit(1)

  def publishLocal(skillDirectories: String): Result[Seq[String], String] =
    Result.catchException { case error => error.toString } {
      result {
        val builtins = Skills.loadFrom(os.pwd / "builtin-skills/capybara-builtins").ok
        val selected = skillDirectories.split(",").toSeq.map(_.trim).map { name =>
          Skills.loadFrom(if name.contains("/") then os.Path(name, os.pwd) else os.pwd / "skills" / name).ok
        }
        val available = selected ++ Skills.loadAll(os.pwd / "skills")
        val dependencies = Skills.resolve(selected.map(_.universe), available, builtins, includeApis = true).ok
        val order = (selected.filter(_.universe == builtins.universe) ++ dependencies).distinctBy(_.universe)
        require(order.nonEmpty, "Select a skill with a public API")
        // Validate the normal build without constructing capabilities. Explicit publication
        // refreshes Ivy even when runtime JARs are cached or the Ivy artifact was removed.
        val _ = ReplExec.prepareSkills(builtins, selected).ok
        val staging = os.temp.dir(prefix = "capybara-publish-")
        try
          for current <- order do
            val source = staging / current.universe / "Interface.scala"
            os.write(source, current.interface, createFolders = true)
            publish(current, source)
        finally os.remove.all(staging)
        order.map(coordinate)
      }
    }.flatMap(identity)
