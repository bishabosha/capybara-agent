//> using options -preview
package system

import scala.math.ScalaNumber
import scala.NamedTuple.NamedTuple
import scala.Conversion.into
import scala.annotation.publicInBinary

sealed trait Showable
class Literal(
    val value: String | Int | Boolean | Long | Unit | Double | Float | Short | Byte | ScalaNumber |
      Number | Null
) extends Showable
class ShowableSeq(val value: Seq[Showable]) extends Showable
class ShowableObj(val value: Map[Showable, Showable]) extends Showable
final class Magnet @publicInBinary private[system] (val inner: Any)
trait BuiltinsLowPrio {
  given [A] => Conversion[A, Magnet] =
    any => Magnet(any)
}
trait Builtins extends BuiltinsLowPrio {
  // TODO: add "deferred" givens to convert named tuple types
  def builtinPrintAndFormatData(any: into[Magnet]): Unit
}
