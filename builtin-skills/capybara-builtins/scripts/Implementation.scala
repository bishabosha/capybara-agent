//> using file Interface.scala
package system_impl
import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe
import scala.Conversion.into
import scala.math.ScalaNumber
import scala.caps.Mutable
import capybara.agent.client.Client
import capybara.agent.client.ClientProvider

@assumeSafe
object builtins extends system.Builtins {
  export ShowableHelpers.baseReflectiveShowable

  def evaluateAndPrintFormatted(any: => into[system.Showable])(using io: Client^): Unit =
    val result = scala.util.Try(any) match
      case scala.util.Success(value) =>
        system.ShowableObj(Map(system.Literal("special.success") -> value))
      case scala.util.Failure(exception) =>
        system.ShowableObj(
          Map(system.Literal("special.error") -> ShowableHelpers.baseReflectiveShowable(exception))
        )
    io.sendMessageRaw(ShowableHelpers.displayShowable(result, isKey = false))
}

private[system_impl] object ShowableHelpers {
  private def isLazySequence(any: Any): Boolean =
    any != null && {
      val name = any.getClass.getName
      name.startsWith("scala.collection.immutable.LazyList") ||
      name.startsWith("scala.collection.immutable.LazyListIterable") ||
      name.startsWith("scala.collection.immutable.Stream")
    }

  private def hasNotComputedTail(any: Any): Boolean =
    isLazySequence(any) && any.toString.contains("<not computed>")

  def baseReflectiveShowable(any: Any): system.Showable =
    if hasNotComputedTail(any) then system.ShowableSeq(Seq(system.Literal("<not computed>")))
    else
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
        case opt: scala.Option[?] =>
          if opt.isEmpty then system.Literal(null)
          else baseReflectiveShowable(opt.get)
        case tryValue: scala.util.Try[?] =>
          tryValue match
            case scala.util.Success(value) =>
              system.ShowableObj(
                Map(system.Literal("_class") -> system.Literal("scala.util.Success"), system.Literal("value") -> baseReflectiveShowable(value))
              )
            case scala.util.Failure(exception) =>
              system.ShowableObj(
                Map(system.Literal("_class") -> system.Literal("scala.util.Failure"), system.Literal("value") -> baseReflectiveShowable(exception))
              )
        case eitherValue: scala.util.Either[?, ?] =>
          eitherValue match
            case scala.util.Left(value) =>
              system.ShowableObj(
                Map(system.Literal("_class") -> system.Literal("scala.Left"), system.Literal("value") -> baseReflectiveShowable(value))
              )
            case scala.util.Right(value) =>
              system.ShowableObj(
                Map(system.Literal("_class") -> system.Literal("scala.Right"), system.Literal("value") -> baseReflectiveShowable(value))
              )
        case throwable: java.lang.Throwable =>
          val showableException = system.ShowableObj(
            Map(
              system.Literal("_runtimeClass") -> system.Literal(throwable.getClass.getName),
              system.Literal("message") -> system.Literal(throwable.getMessage)
            )
          )
          system.ShowableObj(Map(system.Literal("exception") -> showableException))
        case seq: scala.collection.Seq[?] =>
          system.ShowableSeq(seq.map(baseReflectiveShowable).toSeq)
        case arr: Array[?] =>
          system.ShowableSeq(arr.map(baseReflectiveShowable))
        case m: scala.collection.Map[?, ?] =>
          system.ShowableObj(
            m.map { case (k, v) =>
              baseReflectiveShowable(k) -> baseReflectiveShowable(v)
            }.toMap
          )
        case m: scala.collection.Iterable[?] =>
          system.ShowableSeq(m.map(baseReflectiveShowable).toSeq)
        case m: scala.Product =>
          if m.getClass.getName.startsWith("scala.Tuple") then
            system.ShowableSeq(m.productIterator.map(baseReflectiveShowable).toSeq)
          else
            system.ShowableObj(
              m.productElementNames
                .zip(m.productIterator)
                .map { case (k, v) =>
                  baseReflectiveShowable(k) -> baseReflectiveShowable(v)
                }
                .toMap
            )
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
        val simpleKeyCount = showable.value.keys.count {
          case k: system.Literal => true; case _ => false
        }
        if simpleKeyCount == showable.value.size then
          val raw = showable.value
            .map { case (k, v) =>
              s"${displayShowable(k, isKey = true)}: ${displayShowable(v, isKey = false)}"
            }
            .mkString("{", ", ", "}")
          if isKey then asString(raw) else raw
        else
          val raw = showable.value
            .map { case (k, v) =>
              s"[${displayShowable(k, isKey = false)}, ${displayShowable(v, isKey = false)}]"
            }
            .mkString("[", ", ", "]")
          if isKey then asString(raw) else raw
    }
}
