package experiments

import scala.language.experimental.separationChecking
import scala.language.experimental.captureChecking

import caps.assumeSafe
// import caps.Stateful
import scala.caps.SharedCapability

@assumeSafe
case object Done

@assumeSafe
type Done = Done.type

@assumeSafe
trait Consol3 extends SharedCapability {
  def println(msg: String): Unit
}

@assumeSafe
def print_ln(msg: String)(using Consol3): Unit =
  summon[Consol3].println(msg)

@assumeSafe
class Access[T](data: T) extends SharedCapability {
  def map[U](f: T -> U): Access[U] = new Access(f(data))
}

@assumeSafe
object GitHub {

  case class Secret(value: String) extends SharedCapability

  def fetchIssue(using Secret): String = ???

  @assumeSafe
  def login[T](op: Secret ?-> T): T =
    ???


}

@assumeSafe
def accessSecret[T](key: String)(op: Access[Option[String]] -> T): T =
  ???

@assumeSafe
def access(msg: String)(using Consol3): Unit =
  summon[Consol3].println(msg)

@assumeSafe
object Consol3Impl extends Consol3 {
  override def println(msg: String): Unit = {
  // update def println(msg: String): Unit = {
    scala.Console.println(msg)
  }
}

object Application uses Consol3Impl {
  def main(args: Array[String]): Unit =
    println("experiments:")
    println("#" * 25)
    safeMain(using Consol3Impl)
}
