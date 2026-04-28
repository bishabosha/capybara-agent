//> using file Interface.scala
package system_impl
import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe
import scala.Conversion.into
import scala.math.ScalaNumber

@assumeSafe
object builtins extends system.Builtins { self =>

  def convertAsShowable(any: Any): system.Showable =
    any match {
      case s: String       => system.Literal(s)
      case i: Int          => system.Literal(i)
      case b: Boolean      => system.Literal(b)
      case l: Long         => system.Literal(l)
      case d: Double       => system.Literal(d)
      case f: Float        => system.Literal(f)
      case sh: Short       => system.Literal(sh)
      case by: Byte        => system.Literal(by)
      case null            => system.Literal("null")
      case sn: ScalaNumber =>
        system.Literal(sn)
      case seq: scala.collection.Seq[?] =>
        system.ShowableSeq(seq.map(convertAsShowable).toSeq)
      case arr: Array[?] =>
        system.ShowableSeq(arr.map(convertAsShowable))
      case m: scala.collection.Map[?, ?] =>
        system.ShowableObj(
          m.map { case (k, v) =>
            convertAsShowable(k) -> convertAsShowable(v)
          }.toMap
        )
      case m: scala.collection.Iterable[?] =>
        system.ShowableObj(
          m.map { case (k, v) =>
            convertAsShowable(k) -> convertAsShowable(v)
          }.toMap
        )
      case m: scala.Product =>
        system.ShowableObj(
          m.productElementNames
            .zip(m.productIterator)
            .map { case (k, v) =>
              convertAsShowable(k) -> convertAsShowable(v)
            }
            .toMap
        )
      case m: system.Magnet =>
        convertAsShowable(m.inner)
      case other =>
        system.Literal(other.toString)
    }

  def displayShowable(showable: system.Showable): String =
    showable match {
      case showable: system.Literal =>
        showable.value match
          case value: String =>
            '"' + value
              .replaceAllLiterally("\"", "\\\"")
              .replaceAllLiterally("\n", "\\n")
              .replaceAllLiterally("\r", "\\r")
              .replaceAllLiterally("\t", "\\t") + '"'
          case value => value.toString
      case showable: system.ShowableSeq =>
        showable.value.map(displayShowable).mkString("[", ", ", "]")
      case showable: system.ShowableObj =>
        showable.value
          .map { case (k, v) =>
            s"${displayShowable(k)}: ${displayShowable(v)}"
          }
          .mkString("{", ", ", "}")
    }

  def builtinPrintAndFormatData(any: into[system.Magnet]): Unit =
    val showable = convertAsShowable(any.inner)
    println(displayShowable(showable))
}
