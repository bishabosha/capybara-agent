//> using file Interface.scala
package stagedskills.internal

import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path as JPath}
import java.nio.file.StandardOpenOption
import java.util.UUID
import scala.jdk.CollectionConverters.given

private object CurrentSession {
  private val SessionIdProperty = "capybara.agent.stagedSkillSessionId"

  def rootPath(): JPath = {
    val rawSessionId =
      Option(java.lang.System.getProperty(SessionIdProperty))
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse(throw IllegalStateException("missing staged skill session entitlement"))
    val sessionId =
      try UUID.fromString(rawSessionId).toString
      catch {
        case _: IllegalArgumentException =>
          throw IllegalStateException("invalid staged skill session entitlement")
      }
    JPath.of(".agent-runtime", "staged-skills", sessionId).toAbsolutePath.normalize()
  }
}

private final class LocalStagedSkillWorkspace(rootPath: JPath)
    extends stagedskills.StagedSkillWorkspace {
  private val cwdPath = JPath.of(".").toAbsolutePath.normalize()
  private val requiredFiles =
    Vector(
      "SKILL.md",
      "scripts/Interface.scala",
      "scripts/Implementation.scala",
      "scripts/Manifest.scala"
    )

  class Path private[LocalStagedSkillWorkspace] (
      private[LocalStagedSkillWorkspace] val inner: JPath
  ) {
    override def toString: String =
      if inner == rootPath then "."
      else rootPath.relativize(inner).toString
  }

  private def checked(path: JPath): JPath = {
    val normalized = path.toAbsolutePath.normalize()
    if !normalized.startsWith(rootPath) then
      throw IllegalArgumentException(
        s"path escapes staged skill workspace: $normalized"
      )
    rejectSymbolicLinks(normalized)
    normalized
  }

  private def rejectSymbolicLinks(path: JPath): Unit = {
    val start = if path.startsWith(cwdPath) then cwdPath else rootPath
    val relative = start.relativize(path)
    var current = start
    var index = 0
    while index < relative.getNameCount do {
      current = current.resolve(relative.getName(index))
      if Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current) then
        throw IllegalArgumentException(
          s"symbolic links are not visible: ${displayPath(current)}"
        )
      index += 1
    }
  }

  private def displayPath(path: JPath): String =
    if path.startsWith(rootPath) then rootPath.relativize(path).toString
    else path.toString

  private def checkedRelative(relativePath: String): JPath = {
    val raw = JPath.of(relativePath)
    if raw.isAbsolute then
      throw IllegalArgumentException(s"absolute paths are not visible: $relativePath")
    rejectParentSegments(raw, relativePath)
    checked(rootPath.resolve(raw))
  }

  private def rejectParentSegments(path: JPath, original: String): Unit = {
    val iterator = path.iterator()
    while iterator.hasNext do {
      if iterator.next().toString == ".." then
        throw IllegalArgumentException(s"parent path segments are not visible: $original")
    }
  }

  private def checkedSkillName(name: String): String = {
    val raw = JPath.of(name)
    if (
      name.trim.isEmpty ||
      raw.isAbsolute ||
      raw.getNameCount != 1 ||
      name == "." ||
      name == ".."
    ) then
      throw IllegalArgumentException(
        "skill name must be one non-empty directory name inside the staged workspace"
      )
    name
  }

  private def checkedSkillMarkdown(skillMd: String): String = {
    val hasFrontmatter = skillMd.startsWith("---\n") && skillMd.drop(4).contains("\n---")
    val hasName = skillMd.linesIterator.exists(_.startsWith("name:"))
    val hasDescription = skillMd.linesIterator.exists(_.startsWith("description:"))
    if !hasFrontmatter || !hasName || !hasDescription then
      throw IllegalArgumentException(
        "SKILL.md must have YAML frontmatter containing name and description"
      )
    skillMd
  }

  private def directivePrefix(content: String): Vector[String] =
    content.linesIterator
      .dropWhile(_.trim.isEmpty)
      .takeWhile(line => line.startsWith("//> using ") || line.trim.isEmpty)
      .toVector

  private def checkedInterface(interfaceScala: String): String = {
    if directivePrefix(interfaceScala).isEmpty then
      throw IllegalArgumentException(
        "Interface.scala must begin with Scala CLI using directives"
      )
    interfaceScala
  }

  private def checkedImplementation(implementationScala: String): String = {
    val hasInterfaceDirective =
      directivePrefix(implementationScala).contains("//> using file Interface.scala")
    if !hasInterfaceDirective then
      throw IllegalArgumentException(
        "Implementation.scala must include `//> using file Interface.scala` before its package declaration"
      )
    val implValuePattern = raw"(?m)^\s*@assumeSafe\s*\n\s*val\s+impl\s*:\s*[^=]+=".r
    if implValuePattern.findAllMatchIn(implementationScala).size != 1 then
      throw IllegalArgumentException(
        "Implementation.scala must expose exactly one public `@assumeSafe val impl: ExplicitApiInterface = ...`"
      )
    val publicImplDefPattern = raw"(?m)^\s*def\s+impl\b".r
    if publicImplDefPattern.findFirstIn(implementationScala).isDefined then
      throw IllegalArgumentException(
        "Implementation.scala must expose `impl` as a typed value, not a method"
      )
    implementationScala
  }

  private def checkedUniverse(universe: String): String = {
    val trimmed = universe.trim
    if !trimmed.matches("[A-Za-z][A-Za-z0-9_-]*") then
      throw IllegalArgumentException(
        "universe must be a non-empty identifier using letters, digits, underscores, or hyphens"
      )
    trimmed
  }

  private def checkedApi(api: String): String =
    if api.exists(ch => ch == '\n' || ch == '\r') then
      throw IllegalArgumentException("manifest api must be a single-line calling convention")
    else if api.trim.isEmpty then
      throw IllegalArgumentException("manifest api must not be empty")
    else api

  private def scalaStringLiteral(value: String): String = {
    val body =
      value.flatMap {
        case '\\' => "\\\\"
        case '"'  => "\\\""
        case '\n' => "\\n"
        case '\r' => "\\r"
        case '\t' => "\\t"
        case char if java.lang.Character.isISOControl(char) => f"\\u${char.toInt}%04x"
        case char                                           => char.toString
      }
    "\"" + body + "\""
  }

  private def renderManifest(universe: String, api: String, predef: String): String =
    s"""val Manifest = (
       |  universe = ${scalaStringLiteral(checkedUniverse(universe))},
       |  api = ${scalaStringLiteral(checkedApi(api))},
       |  predef = ${scalaStringLiteral(predef)}
       |)
       |""".stripMargin

  private def skillFile(skillName: String, relativeFile: String): JPath =
    checkedRelative(s"$skillName/$relativeFile")

  private def checklistFor(skillName: String): stagedskills.SkillChecklist = {
    val written =
      requiredFiles.filter { file =>
        Files.exists(skillFile(skillName, file), LinkOption.NOFOLLOW_LINKS)
      }
    stagedskills.SkillChecklist(
      skill = skillName,
      written = written,
      remaining = requiredFiles.filterNot(written.toSet),
      complete = written.size == requiredFiles.size
    )
  }

  private def wrap(path: JPath): Path = Path(checked(path))

  private def ensureWritableFile(path: JPath): Unit = {
    val parent = Option(path.getParent).getOrElse(rootPath)
    Files.createDirectories(checked(parent))
    if Files.exists(path) && Files.isDirectory(path) then
      throw IllegalArgumentException(
        s"cannot write file over directory: ${rootPath.relativize(path)}"
      )
  }

  def root(): Path = wrap(rootPath)

  def path(relativePath: String): Path = wrap(checkedRelative(relativePath))

  def list(path: Path): Seq[Path] = {
    val target = checked(path.inner)
    if target == rootPath && !Files.exists(target, LinkOption.NOFOLLOW_LINKS) then Seq.empty
    else {
      if !Files.isDirectory(target) then
        throw IllegalArgumentException(s"not a directory: ${rootPath.relativize(target)}")
      val stream = Files.list(target)
      try stream.iterator().asScala.map(wrap).toSeq.sortBy(_.toString)
      finally stream.close()
    }
  }

  def read(path: Path): String = Files.readString(checked(path.inner), StandardCharsets.UTF_8)

  def write(path: Path, content: String): Path = {
    val target = checked(path.inner)
    ensureWritableFile(target)
    Files.writeString(
      target,
      content,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
    wrap(target)
  }

  def makeDirectories(path: Path): Path = {
    val target = checked(path.inner)
    Files.createDirectories(target)
    wrap(target)
  }

  def exists(path: Path): Boolean = Files.exists(checked(path.inner))

  def checklist(name: String): stagedskills.SkillChecklist =
    checklistFor(checkedSkillName(name))

  def writeSkillMarkdown(name: String, skillMd: String): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    write(wrap(skillFile(skillName, "SKILL.md")), checkedSkillMarkdown(skillMd))
    checklistFor(skillName)
  }

  def writeInterface(name: String, interfaceScala: String): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    write(
      wrap(skillFile(skillName, "scripts/Interface.scala")),
      checkedInterface(interfaceScala)
    )
    checklistFor(skillName)
  }

  def writeImplementation(
      name: String,
      implementationScala: String
  ): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    write(
      wrap(skillFile(skillName, "scripts/Implementation.scala")),
      checkedImplementation(implementationScala)
    )
    checklistFor(skillName)
  }

  def writeManifest(
      name: String,
      universe: String,
      api: String,
      predef: String
  ): stagedskills.SkillChecklist = {
    val skillName = checkedSkillName(name)
    val manifestPath = skillFile(skillName, "scripts/Manifest.scala")
    write(wrap(manifestPath), renderManifest(universe, api, predef))
    checklistFor(skillName)
  }
}

private object Provider extends stagedskills.StagedSkillWorkspaceProvider {
  def current(): stagedskills.StagedSkillWorkspace =
    LocalStagedSkillWorkspace(CurrentSession.rootPath())
}

@assumeSafe
val impl: stagedskills.StagedSkillWorkspaceProvider = Provider
