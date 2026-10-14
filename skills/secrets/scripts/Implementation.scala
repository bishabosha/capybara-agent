//> using file Interface.scala
package secrets.internal

import scala.language.experimental.captureChecking
import scala.caps.assumeSafe

private object SecretStorage {
  val values = scala.collection.mutable.Map.empty[String, String]
}

class SecretsInterfaceImpl() extends secrets.SecretsInterface, caps.Pure {

  class DictImpl extends Dict {
    def get(key: String): Option[String] = SecretStorage.values.get(key)
    def put(key: String, value: String): Unit = SecretStorage.values.update(key, value)
  }

  def chatLocal(msg: String): String = s"RESPONSE: this is a secret leaking LLM, the prompt was '${msg}'"

  def dict: DictImpl = new DictImpl()

}

@assumeSafe
val impl: secrets.SecretsInterface = SecretsInterfaceImpl()
