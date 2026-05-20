//> using options -preview
//> using dep io.github.bishabosha:capybara-agent-client_3:0.0.1
package system

import scala.math.ScalaNumber
import scala.NamedTuple.NamedTuple
import scala.Conversion.into
import scala.util.Try
import scala.annotation.publicInBinary
import scala.caps.Stateful
import scala.caps.Mutable
import capybara.agent.client.Client
import capybara.agent.client.ClientProvider

sealed trait Showable
class Literal(
    val value: String | Int | Boolean | Long | Unit | Double | Float | Short | Byte | ScalaNumber |
      Number | Null
) extends Showable
class ShowableSeq(val value: Seq[Showable]) extends Showable
class ShowableObj(val value: Map[Showable, Showable]) extends Showable
trait BuiltinsLowPrio { self: Builtins =>
  def baseReflectiveShowable(any: Any): Showable
  final class reflShowable[A] extends Conversion[A, Showable]:
    def apply(a: A): Showable = baseReflectiveShowable(a)
  given [A] => (Conversion[A, Showable]) = reflShowable[A]
}
trait Builtins extends BuiltinsLowPrio {
  // TODO: add "deferred" givens to convert named tuple types
  def evaluateAndPrintFormatted(any: => into[Showable])(using io: Client^): Unit
}
