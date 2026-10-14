package capybara.agent

import scalanotation.Readers
import steps.result.Result, Result.eval.ok
import Result.apply as result
import os.{Path, read, list, exists, isDir}
import org.virtuslab.yaml.*

type SkillBinding = (name: String, tpe: String, value: String)
type SkillManifest = (universe: String, api: String, predef: Option[String], bindings: Seq[SkillBinding])

case class Skill(
    name: String,
    description: String,
    universe: String,
    interface: String,
    code: String,
    api: String,
    predef: Option[String],
    requiresRuntimeClasspath: Boolean = true,
    bindings: Seq[SkillBinding] = Nil
)

case class SkillMd(
    name: String,
    description: String
) derives YamlDecoder

object Skills {

  val Basic: Skill =
    Skill(
      name = "basic",
      description =
        "Runs Scala code with only the Scala and Java standard libraries. Use for arithmetic, strings, collections, dates, parsing, and other basic computation that does not need files, network, shell commands, or a skill-specific API.",
      universe = "basic",
      interface = "",
      code = "",
      api = """No skill-specific capabilities are supplied.
        |Write a Scala expression or block directly; call `puts(value)` to emit the result.
        |Use little or no internal reasoning for this universe.
        |Keep code short and direct for simple tasks; avoid helper methods unless they materially clarify the computation.
        |When a vague optimization preference is given for a small bounded task, use the straightforward efficient approach; do not debate numeric representation unless it affects the requested result.
        |Do not mention whether standard library or Predef methods are available before calling the tool.
        |Do not call global `println`; use `puts(value)` for structured output.
        |The final expression is discarded. Without an explicit print, successful execution has no output.
        |Example `scala_code`: `puts(List(1, 2, 3).sum)`
        |""".stripMargin,
      predef = None,
      requiresRuntimeClasspath = false
    )

  def parseSkill(content: String): Result[SkillMd, String] =
    content match
      case s"""---\n$yaml\n---\n${_}""" =>
        yaml.as[SkillMd] match
          case Right(md) => Result.Ok(md)
          case Left(e)   => Result.Err(s"skill md parse error: $e")

      case _ => Result.Err("invalid manifest format")

  def parseManifest(content: String): Result[SkillManifest, String] =
    Readers
      .readDeclAs[SkillManifest](content, rootName = "Manifest")
      .mapErr(e => s"manifest parse error: ${e.format}")

  def readSafely(path: Path): Result[String, String] =
    Result.catchException({ case e =>
      s"error reading file $path: ${e.getMessage}"
    })(read(path))

  def loadFrom(dir: Path): Result[Skill, String] = result {
    val skillMd = dir / "SKILL.md"
    val interfaceFile = dir / "scripts/Interface.scala"
    val implFile = dir / "scripts/Implementation.scala"
    val manifestFile = dir / "scripts/Manifest.scala"

    val skill = parseSkill(readSafely(skillMd).ok).ok
    val manifest = parseManifest(readSafely(manifestFile).ok).ok
    val interfaceCode = readSafely(interfaceFile).ok
    val implCode = readSafely(implFile).ok
    Skill(
      name = skill.name,
      description = skill.description,
      universe = manifest.universe,
      interface = interfaceCode,
      code = implCode,
      api = manifest.api,
      predef = manifest.predef.filter(_.nonEmpty),
      bindings = manifest.bindings
    )
  }

  def loadAll(dir: Path): Seq[Skill] =
    // Todo: errors are swallowed
    if isDir(dir) then list(dir).filter(p => exists(p) && isDir(p)).flatMap(loadFrom)
    else Nil

  def renderPrompt(sep: String, skills: Seq[Skill]): String = {
    skills
      .map { skill =>
        if skill.requiresRuntimeClasspath then
          s"""#### ${skill.universe}
          |${skill.description}
          |```scala
          |${skill.interface}
          |```
          |Calling convention:
          |${skill.api}
          |The harness supplies these named contextual parameters to your function (do not create them yourself):
          |```scala
          |${skill.bindings.map(b => s"${b.name}: ${b.tpe}").mkString("(using ", ", ", ")")}
          |```
          |""".stripMargin
        else
          s"""#### ${skill.universe}
          |${skill.description}
          |
          |${skill.api}
          |This universe does not compile or load a universe-specific implementation JAR beyond the baseline builtins.
          |""".stripMargin
      }
      .mkString(sep)
  }
}
