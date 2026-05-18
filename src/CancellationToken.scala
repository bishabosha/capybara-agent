package capybara.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

final class CancellationToken:
  private val cancelled = AtomicBoolean(false)
  private val callbacks = ConcurrentLinkedQueue[Runnable]()

  def cancel(): Unit =
    if cancelled.compareAndSet(false, true) then
      val iterator = callbacks.iterator()
      while iterator.hasNext do
        try iterator.next().run()
        catch case _: Throwable => ()
      callbacks.clear()

  def isCancelled: Boolean =
    cancelled.get()

  def onCancel(callback: => Unit): AutoCloseable =
    val runnable = new Runnable:
      def run(): Unit = callback

    if isCancelled then
      runnable.run()
      CancellationToken.NoopCloseable
    else
      val _ = callbacks.add(runnable)
      if isCancelled && callbacks.remove(runnable) then runnable.run()
      new AutoCloseable:
        def close(): Unit =
          val _ = callbacks.remove(runnable)
          ()

object CancellationToken:
  private object NoopCloseable extends AutoCloseable:
    def close(): Unit = ()

  val Never: CancellationToken = CancellationToken()
