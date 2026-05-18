package capybara.agent

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

object RuntimeStatus:
  private final case class Stopwatch(startedAtNanos: Long = System.nanoTime()):
    def elapsedSeconds: Long =
      ((System.nanoTime() - startedAtNanos) / 1_000_000_000L).max(0L)

  final case class HeapSnapshot(usedBytes: Long, committedBytes: Long, maxBytes: Long):
    private def mib(bytes: Long): Long =
      math.max(0L, bytes / (1024L * 1024L))

    def render: String =
      val used = mib(usedBytes)
      val max =
        if maxBytes <= 0L || maxBytes == Long.MaxValue then "unbounded"
        else s"${mib(maxBytes)} MiB"
      val percent =
        if maxBytes <= 0L || maxBytes == Long.MaxValue then ""
        else s", ${math.min(100L, (usedBytes * 100L) / maxBytes)} pct"
      s"heap $used MiB/$max$percent"

  private val scheduler =
    Executors.newSingleThreadScheduledExecutor(
      new ThreadFactory:
        def newThread(runnable: Runnable): Thread =
          val thread = new Thread(runnable, "capybara-heap-status")
          thread.setDaemon(true)
          thread
    )

  def heapSnapshot(): HeapSnapshot =
    val runtime = Runtime.getRuntime
    HeapSnapshot(
      usedBytes = runtime.totalMemory() - runtime.freeMemory(),
      committedBytes = runtime.totalMemory(),
      maxBytes = runtime.maxMemory()
    )

  def heapStatus: String =
    heapSnapshot().render

  def appendHeap(message: String): String =
    s"$message, $heapStatus"

  private def appendElapsedAndHeap(message: String, stopwatch: Stopwatch): String =
    s"$message, ${stopwatch.elapsedSeconds}s, $heapStatus"

  def monitor(
      log: Logger,
      label: String,
      intervalMillis: Long = 500L,
      shouldUpdate: () => Boolean = () => true
  ): AutoCloseable =
    val stopwatch = Stopwatch()
    val task = new Runnable:
      def run(): Unit =
        if shouldUpdate() then
          log.setStatus(
            Some(s"${Console.CYAN}[${appendElapsedAndHeap(label, stopwatch)}]${Console.RESET}")
          )

    task.run()
    val scheduled: ScheduledFuture[?] =
      scheduler.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)

    new AutoCloseable:
      def close(): Unit =
        val _ = scheduled.cancel(false)
        ()
