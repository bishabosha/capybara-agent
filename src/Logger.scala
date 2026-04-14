package capybara.agent

import java.io.PrintWriter

trait Logger:
  def print(message: String): Unit
  def flush(): Unit

object Logger:

  object ConsoleLogger extends Logger:
    def print(message: String): Unit = Console.err.print(message)
    def flush(): Unit = ()

  class TerminalLogger(ps: PrintWriter) extends Logger with AutoCloseable:
    def close(): Unit = flushToWriter()

    private def appendPrinted(chunk: String): Unit =
      // TODO: for now it appears that streaming LLM chunks is slow enough that
      // flushing immediately is fine.
      // for more frequent writes perhaps we could implement a buffer and delayed flushing strategies.
      ps.write(chunk)
      ps.flush()

    private def flushToWriter(): Unit =
      ps.flush()

    def print(message: String): Unit = appendPrinted(message)
    def flush(): Unit = flushToWriter()
