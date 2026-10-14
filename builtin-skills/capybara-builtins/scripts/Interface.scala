//> using options -preview
package system

import scala.math.ScalaNumber
import scala.Conversion.into

import scala.language.experimental.captureChecking

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

  trait OutputStreams extends caps.SharedCapability {
    /** Emit one structured value to stdout. Final expression values are discarded. */
    def print(value: into[Showable]): Unit
    /** Emit one structured error. Final expression values are discarded. */
    def printErr(err: into[system.Showable]): Unit
  }

  final def puts(value: into[Showable])(using OutputStreams): Unit = summon[OutputStreams].print(value)
  final def putsErr(err: into[Showable])(using OutputStreams): Unit = summon[OutputStreams].printErr(err)

}
