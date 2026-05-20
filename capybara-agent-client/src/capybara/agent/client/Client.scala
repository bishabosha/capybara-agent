package capybara.agent.client

import language.experimental.captureChecking
import language.experimental.separationChecking

import caps.assumeSafe
import caps.Mutable
import scala.annotation.publicInBinary
// import scala.caps.Stateful
// import scala.caps.SharedCapability
// import scala.caps.Unscoped

trait Server extends Mutable:
  update def readMessageRaw(): String

@assumeSafe
trait Client extends Mutable:
  update def sendMessageRaw(message: String): Unit

trait IO extends Client, Server {
  def isBuffered: Boolean
}

@assumeSafe
trait ClientProvider extends Mutable:
  update def client[T](f: Client^ ?=> T): T

@assumeSafe
object ClientProvider:
  def apply(): ClientProvider^ = IOProvider.BasicProvider()

trait ServerProvider extends Mutable:
  update def server[T](f: Server^ ?=> T): T

object ServerProvider:
  def apply(): ServerProvider^ = IOProvider.BasicProvider()

trait IOProvider extends ClientProvider, ServerProvider:
  update def io[T](f: IO^ ?=> T): T

object IOProvider:
  def apply(): IOProvider^ = BasicProvider()

  class BasicProvider extends IOProvider:
    @publicInBinary
    private[BasicProvider] val live: BasicIO^ = new BasicIO
    update def client[T](f: Client^ ?=> T): T =
      f(using live)
    update def server[T](f: Server^ ?=> T): T =
      f(using live)
    update def io[T](f: IO^ ?=> T): T =
      f(using live)

  object global extends BasicProvider

  class BasicIO extends IO:
    private val messages = java.util.concurrent.LinkedBlockingQueue[String]()
    def isBuffered: Boolean = messages.size() > 0

    override update def sendMessageRaw(message: String): Unit =
      messages.put(message)

    override update def readMessageRaw(): String =
      messages.take()
