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
      case null            => system.Literal(null)
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

  def displayShowable(showable: system.Showable, isKey: Boolean): String =
    def asString(str: String): String =
      '"' + str
        .replaceAllLiterally("\"", "\\\"")
        .replaceAllLiterally("\n", "\\n")
        .replaceAllLiterally("\r", "\\r")
        .replaceAllLiterally("\t", "\\t") + '"'
    showable match {
      case showable: system.Literal =>
        val (raw, isString) = showable.value match
          case null          => ("null", false)
          case value: String => (value, true)
          case value         => (value.toString, false)
        if isKey || isString then asString(raw) else raw
      case showable: system.ShowableSeq =>
        val raw = showable.value.map(displayShowable(_, isKey = false)).mkString("[", ", ", "]")
        if isKey then asString(raw) else raw
      case showable: system.ShowableObj =>
        val raw = showable.value
          .map { case (k, v) =>
            s"${displayShowable(k, isKey = true)}: ${displayShowable(v, isKey = false)}"
          }
          .mkString("{", ", ", "}")
        if isKey then asString(raw) else raw
    }

  def builtinPrintAndFormatData(any: into[system.Magnet]): Unit =
    val showable = convertAsShowable(any.inner)
    println(displayShowable(showable, isKey = false))
}
