//> using options -preview
package system

import scala.math.ScalaNumber
import scala.NamedTuple.NamedTuple
import scala.Conversion.into
import scala.util.Try
import scala.annotation.publicInBinary

sealed trait Showable
class Literal(
    val value: String | Int | Boolean | Long | Unit | Double | Float | Short | Byte | ScalaNumber |
      Number | Null
) extends Showable
class ShowableSeq(val value: Seq[Showable]) extends Showable
class ShowableObj(val value: Map[Showable, Showable]) extends Showable
trait BuiltinsLowPrio extends caps.Pure {
  def baseReflectiveShowable(any: Any): Showable
  given [A] => Conversion[A, Showable]:
    def apply(a: A): Showable = baseReflectiveShowable(a)
}
trait Builtins extends BuiltinsLowPrio {
  // TODO: add "deferred" givens to convert named tuple types
  def evaluateAndPrintFormatted(any: => into[Showable]): Unit
}
