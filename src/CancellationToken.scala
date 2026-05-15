package capybara.agent

import java.util.concurrent.atomic.AtomicBoolean

final class CancellationToken:
  private val cancelled = AtomicBoolean(false)

  def cancel(): Unit =
    cancelled.set(true)

  def isCancelled: Boolean =
    cancelled.get()

object CancellationToken:
  val Never: CancellationToken = CancellationToken()
