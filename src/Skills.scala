package capybara.agent

import scalanotation.Readers
import steps.result.Result, Result.eval.ok
import Result.apply as result
import os.{Path, read, list, exists, isDir}
import org.virtuslab.yaml.*

type SkillBinding = (name: String, tpe: String, value: String)
type SkillManifest = (universe: String, api: String, predef: Option[String], bindings: Seq[SkillBinding])
type ComposedSkillManifest = (universe: String, api: String, predef: Option[String], bindings: Seq[SkillBinding], requires: Option[Seq[String]])

case class Skill(
    name: String,
    description: String,
    universe: String,
    interface: String,
    code: String,
    api: String,
    predef: Option[String],
    requiresRuntimeClasspath: Boolean = true,
    bindings: Seq[SkillBinding] = Nil,
    requires: Seq[String] = Nil,
    apiDependencies: Seq[String] = Nil
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
        "Runs Scala safe-mode code using permitted Scala and Java standard-library APIs. Prefer immutable collections. Use for basic computation without files, network, shell commands, or a skill-specific API.",
      universe = "basic",
      interface = "",
      code = "",
      api = """No skill-specific capabilities are supplied.
        |Safe mode permits only checked or explicitly trusted APIs, not every standard-library API.
        |Prefer immutable List/Vector and map/foldLeft; do not use mutable ListBuffer/ArrayBuffer.
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
    val manifestText = readSafely(manifestFile).ok
    val dependencyPattern = "(?s).*\\brequires\\s*=.*".r
    val (manifest, dependencies) = manifestText match
      case dependencyPattern() =>
        val composed = Readers.readDeclAs[ComposedSkillManifest](manifestText, rootName = "Manifest")
          .mapErr(e => s"manifest parse error: ${e.format}").ok
        ((universe = composed.universe, api = composed.api, predef = composed.predef,
          bindings = composed.bindings), composed.requires.getOrElse(Nil))
      case _ => (parseManifest(manifestText).ok, Nil)
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
      bindings = manifest.bindings,
      requires = dependencies.distinct,
      apiDependencies = SkillArtifacts.dependencies(interfaceCode, implCode)
    )
  }

  /** Dependency order is also factory-construction order. Only declared safe APIs form compile classpaths. */
  def resolve(requested: Seq[String], available: Seq[Skill], builtins: Skill,
              includeApis: Boolean = false): Result[Vector[Skill], String] =
    Result.catchException { case error => error.getMessage } {
      val known = (available :+ builtins).groupBy(_.universe).view.mapValues(_.head).toMap
      val completed = scala.collection.mutable.Set.empty[String]
      val active = scala.collection.mutable.Set.empty[String]
      val ordered = Vector.newBuilder[Skill]
      def visit(name: String): Unit =
        if !completed(name) then
          require(!active(name), s"Skill dependency cycle at: $name")
          val skill = known.getOrElse(name, throw IllegalArgumentException(s"Universe not found: $name"))
          require(skill.name == skill.universe, s"Skill name must match universe: $name")
          active += name
          skill.requires.foreach(visit)
          if includeApis then skill.apiDependencies.filter(known.contains).foreach(visit)
          active -= name
          completed += name
          if skill.requiresRuntimeClasspath && name != builtins.universe then ordered += skill
      requested.foreach(visit)
      val skills = ordered.result()
      if !includeApis then
        val _ = SkillScaffold.bindings(builtins, skills)
      skills
    }

  def loadAll(dir: Path): Seq[Skill] =
    // Todo: errors are swallowed
    if isDir(dir) then list(dir).filter(p => exists(p) && isDir(p)).flatMap(loadFrom)
    else Nil

  def renderPrompt(sep: String, skills: Seq[Skill], includeInterfaces: Boolean = true): String = {
    skills
      .map { skill =>
        if skill.requiresRuntimeClasspath then
          val reference = if includeInterfaces then
            s"""Public API reference (already compiled; read these declarations, do not copy them into scala_code):
               |```scala
               |${skill.interface}
               |```""".stripMargin
          else
            s"""Inspect method signatures with the inspect-scala-api skill before calling an unfamiliar API.
               |Select universes = ["inspect-scala-api", "${skill.universe}"] and call
               |puts(inspectScalaApi.get("${skill.universe}", "${skill.bindings.headOption.map(_.tpe).getOrElse("<fully-qualified type>")}")).
               |The inspector also provides list(skill, packageOrType) and search(skill, query).""".stripMargin
          s"""#### ${skill.universe}
          |${skill.description}
          |
          |Use run_scala_code with universe = "${skill.universe}" and write only the snippet body.
          |The harness supplies these existing capability values; call methods on them directly:
          |${skill.bindings.map(b => s"- `${b.name}: ${b.tpe}`").mkString("\n")}
          |Do not implement the public interfaces, instantiate capabilities, or write a using wrapper.
          |
          |Calling instructions:
          |${skill.api}
          |Required skills (loaded automatically): ${if skill.requires.isEmpty then "none" else skill.requires.mkString(", ")}
          |
          |$reference
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
