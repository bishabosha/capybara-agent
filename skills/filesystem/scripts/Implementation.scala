//> using file Interface.scala
package fs.internal
import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe
import java.nio.file.{Paths as JPaths, Path as JPath, Files}
import scala.jdk.CollectionConverters.given

private object PrivateFilesystem extends fs.Filesystem {
  class Path(val inner: JPath) {
    override def toString = inner.toString
  }
  object Path {
    def unapply(path: Path): Some[JPath] = Some(path.inner)
  }
  def pwd(): Path = Path(JPaths.get("."))
  def list(path: Path): Seq[Path] =
    path match {
      case Path(p) => Files.list(p).iterator().asScala.map(o => Path(o)).toSeq
    }
}

@assumeSafe
val impl: fs.Filesystem = PrivateFilesystem
