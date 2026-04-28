//> using file Interface.scala
package fs_impl
import scala.language.experimental.{captureChecking, separationChecking}
import java.nio.file.{Paths as JPaths, Path as JPath, Files}
import caps.assumeSafe
import scala.jdk.CollectionConverters.given
@assumeSafe
object filesystem extends fs.Filesystem { self =>
  class P(val inner: JPath) extends Path {
    override def toString = inner.toString
  }
  object P {
    def unapply(path: P): Some[JPath] = Some(path.inner)
  }
  def pwd(): P = P(JPaths.get("."))
  def list(path: Path): Seq[P] =
    path match {
      case P(p) => Files.list(p).iterator().asScala.map(o => P(o)).toSeq
      case _    => ???
    }
}
