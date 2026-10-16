package capybara.skills.secrets

import scala.language.experimental.captureChecking
import scala.caps.SharedCapability

trait SecretAccess {

  trait Dict extends SharedCapability {
    def get(key: String): Option[String]
    def put(key: String, value: String): Unit
  }

  class Access[T](data: T) extends SharedCapability {
    def map[U](f: T -> U): Access[U] = new Access(f(data))
  }

  def chatLocal(msg: String): String

  def accessSecret[T](key: String)(op: Access[Option[String]] -> T): T =
    op(new Access(dict.get(key)))

  def dict: Dict

}
