//> using file Interface.scala
package capybara.skills.filesystem.internal

import scala.language.experimental.captureChecking
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The trusted implementation supplies file effects; snippets receive only FileSystem. */
private class WorkspaceFileSystem(workspace: String) extends capybara.skills.filesystem.FileSystem {
  private val root = Path.of(workspace).toRealPath()
  private val maximumBytes = 1024 * 1024
  private class FileError(code: String) extends IllegalStateException(code)

  private def checked[A](action: => A): A =
    try action
    catch
      case error: FileError => throw error
      case NonFatal(_) => throw FileError("FILE_OPERATION_FAILED")

  private def resolve(name: String): Path =
    val relative = Path.of(name)
    if relative.isAbsolute || relative.iterator().asScala.exists(_.toString == "..") then
      throw FileError("PATH_DENIED")
    val path = root.resolve(relative).normalize()
    if !path.startsWith(root) then throw FileError("PATH_DENIED")
    var component = root
    for part <- root.relativize(path).iterator().asScala do
      component = component.resolve(part)
      if Files.isSymbolicLink(component) then throw FileError("PATH_DENIED")
    path

  private def regular(path: Path): Unit =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then throw FileError("NOT_A_TEXT_FILE")

  def list(path: String): Seq[String] = checked {
    val directory = resolve(path)
    val stream = Files.newDirectoryStream(directory)
    try stream.iterator().asScala.toVector
      .filterNot(Files.isSymbolicLink(_))
      .map(root.relativize(_).toString.replace(java.io.File.separatorChar, '/')).sorted
    finally stream.close()
  }

  def read(path: String): String = checked {
    val target = resolve(path)
    regular(target)
    val input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)
    val bytes = try input.readNBytes(maximumBytes + 1) finally input.close()
    if bytes.length > maximumBytes then throw FileError("FILE_TOO_LARGE")
    UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString
  }

  private def store(path: String, text: String): Unit =
    val bytes = text.getBytes(UTF_8)
    if bytes.length > maximumBytes then throw FileError("FILE_TOO_LARGE")
    val target = resolve(path)
    if target == root then throw FileError("PATH_DENIED")
    if Files.exists(target, LinkOption.NOFOLLOW_LINKS) then regular(target)
    val parent = target.getParent
    if !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) then throw FileError("PARENT_NOT_FOUND")
    val temporary = Files.createTempFile(parent, ".capybara-edit-", ".tmp")
    try
      val _ = Files.write(temporary, bytes)
      if Files.exists(target) && Files.getFileStore(target).supportsFileAttributeView("posix") then
        val _ = Files.setPosixFilePermissions(temporary,
          Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS))
      // A new inode avoids modifying another file through a hard-linked destination.
      val _ = Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally
      val _ = Files.deleteIfExists(temporary)

  def write(path: String, text: String): Unit = checked { store(path, text) }

  def pwd(): String = "."

}

@caps.rejectSafe
def impl(): capybara.skills.filesystem.FileSystem =
  configured(sys.env.getOrElse("CAPYBARA_WORKSPACE_ROOT",
    sys.env.getOrElse("CAPYBARA_CODING_ROOT", Path.of("").toAbsolutePath.toString)))

/** Only trusted harness wiring can select the workspace. */
@caps.rejectSafe
def configured(root: String): capybara.skills.filesystem.FileSystem = new WorkspaceFileSystem(root)
