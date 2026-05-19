//> using file Interface.scala
package stagedskills.internal

import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path as JPath}
import java.nio.file.StandardOpenOption
import scala.jdk.CollectionConverters.given

private final class LocalStagedSkillWorkspace extends stagedskills.StagedSkillWorkspace {
  private val rootPath = JPath.of(__CAPYBARA_STAGED_SKILL_ROOT__).toAbsolutePath.normalize()

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
    val relative = rootPath.relativize(path)
    var current = rootPath
    var index = 0
    while index < relative.getNameCount do {
      current = current.resolve(relative.getName(index))
      if Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current) then
        throw IllegalArgumentException(
          s"symbolic links are not visible: ${rootPath.relativize(current)}"
        )
      index += 1
    }
  }

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

  def writeSkill(
      name: String,
      skillMd: String,
      interfaceScala: String,
      implementationScala: String,
      manifestScala: String
  ): Path = {
    val skillName = checkedSkillName(name)
    val skillDir = checkedRelative(skillName)
    val scriptsDir = checked(skillDir.resolve("scripts"))
    Files.createDirectories(scriptsDir)
    write(wrap(skillDir.resolve("SKILL.md")), skillMd)
    write(wrap(scriptsDir.resolve("Interface.scala")), interfaceScala)
    write(wrap(scriptsDir.resolve("Implementation.scala")), implementationScala)
    write(wrap(scriptsDir.resolve("Manifest.scala")), manifestScala)
    wrap(skillDir)
  }
}

@assumeSafe
val impl: stagedskills.StagedSkillWorkspace = LocalStagedSkillWorkspace()
