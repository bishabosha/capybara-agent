package experiments

import scala.language.experimental.separationChecking
import scala.language.experimental.captureChecking

import caps.assumeSafe
import scala.Conversion.into
// import caps.Stateful
import scala.caps.SharedCapability
import scala.caps.any
import scala.caps.rejectSafe

@assumeSafe
trait Consol3 extends SharedCapability {
  // def println(msg: PureString): Unit
  def println(msg: String): Unit
}

// case class PureString(@rejectSafe val value: String):
//   def +(other: PureString): PureString =
//     PureString(this.value + other.value)

// extension (ctx: StringContext)
//   def pure(args: PureString*): PureString =
//     PureString(ctx.parts.zipAll(args, "", PureString("")).map { case (part, arg) => part + arg.value }.mkString)

@assumeSafe
def print_ln(msg: String)(using Consol3): Unit =
// def print_ln(msg: PureString)(using Consol3): Unit =
  summon[Consol3].println(msg)

@assumeSafe
class Access[T](data: T) extends SharedCapability {
  def map[U](f: T -> U): Access[U] = new Access(f(data))
}

@assumeSafe
object GitHub {

  case class Secret(value: String) extends caps.Classifier, SharedCapability

  def fetchIssue(secret: Secret): String =
    // simulate I/O to use secret
    """{
    |  "id": 123,
    |  "title": "Sample Issue"
    |}""".stripMargin

  @assumeSafe
  def session[T](op: Secret -> T): T =
    op(Secret("gh_sk_94394flkdsfkjsd84"))


}

@assumeSafe
def accessSecret[T](key: String)(op: Access[Option[String]] -> T): T =
  ???

@assumeSafe
def access(msg: String)(using Consol3): Unit =
  ???

@assumeSafe
object Consol3Impl extends Consol3 {
  // override def println(msg: PureString): Unit = {
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
