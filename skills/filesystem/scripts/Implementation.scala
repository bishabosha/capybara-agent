//> using file Interface.scala
package fs_impl
import scala.language.experimental.{captureChecking, separationChecking}
import java.nio.file.{Paths as JPaths, Path as JPath, Files}
import caps.assumeSafe

@assumeSafe
object filesystem extends fs.Filesystem { self =>
  case class P(inner: JPath) extends Path
  def pwd(): P = P(JPaths.get("."))
  def list(path: Path): Seq[P] =
    path match {
      case P(p) => Files.list(p).toArray().map(o => P(o.asInstanceOf[JPath])).toSeq
      case _    => ???
    }
}
