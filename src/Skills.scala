package capybara.agent

import scalanotation.Readers
import steps.result.Result, Result.eval.ok
import Result.apply as result
import os.{Path, read, list, exists, isDir}
import org.virtuslab.yaml.*

type SkillManifest = (universe: String, api: String, predef: Option[String])

case class Skill(
    name: String,
    description: String,
    universe: String,
    interface: String,
    code: String,
    api: String,
    predef: Option[String]
)

case class SkillMd(
    name: String,
    description: String
) derives YamlDecoder

object Skills {

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
    val description = readSafely(skillMd).ok
    Skill(
      name = skill.name,
      description = skill.description,
      universe = manifest.universe,
      interface = interfaceCode,
      code = implCode,
      api = manifest.api,
      predef = manifest.predef.filter(_.nonEmpty)
    )
  }

  def loadAll(dir: Path): Seq[Skill] =
    // Todo: errors are swallowed
    if isDir(dir) then list(dir).filter(p => exists(p) && isDir(p)).flatMap(loadFrom)
    else Nil

  def renderPrompt(sep: String, skills: Seq[Skill]): String = {
    skills
      .map { skill =>
        s"""#### ${skill.universe}
        |${skill.description}
        |```scala
        |${skill.interface}
        |```
        |before your code is executed, the following preable is inserted beforehand (DO NOT REPEAT THIS IN YOUR CODE):
        |```scala
        |${skill.api}
        |```
        |""".stripMargin
      }
      .mkString(sep)
  }
}
