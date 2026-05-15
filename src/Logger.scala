package capybara.agent

import java.io.PrintWriter
import org.jline.reader.LineReader
import org.jline.reader.impl.LineReaderImpl

trait Logger:
  def print(message: String): Unit
  def flush(): Unit
  def setStatus(message: Option[String]): Unit = ()
  def prompt(basePrompt: String): String = basePrompt

object Logger:

  object ConsoleLogger extends Logger:
    def print(message: String): Unit = Console.err.print(message)
    def flush(): Unit = ()

  class TerminalLogger(reader: LineReader, ps: PrintWriter, basePrompt: String)
      extends Logger
      with AutoCloseable:
    def close(): Unit =
      setStatus(None)
      flushToWriter()

    private val pendingOutput = StringBuilder()
    private var activeStyle = ""
    private var statusLine = Option.empty[String]

    private def printAboveClean(message: String): Unit =
      reader.printAbove(resetWrapped(message))

    override def prompt(basePrompt: String): String = this.synchronized {
      statusLine match
        case Some(message) => s"${Console.RESET}$message${Console.RESET}\n$basePrompt"
        case None          => basePrompt
    }

    private def redrawPrompt(): Unit =
      val nextPrompt = statusLine match
        case Some(message) => s"${Console.RESET}$message${Console.RESET}\n$basePrompt"
        case None          => basePrompt
      reader match
        case impl: LineReaderImpl =>
          impl.setPrompt(nextPrompt)
          if reader.isReading then reader.callWidget(LineReader.REDISPLAY)
        case _ => ()

    private def resetWrapped(message: String): String =
      val lineEnding =
        if message.endsWith("\r\n") then "\r\n"
        else if message.endsWith("\n") then "\n"
        else ""
      if lineEnding.nonEmpty then
        Console.RESET + message.dropRight(lineEnding.length) + Console.RESET + lineEnding
      else Console.RESET + message + Console.RESET

    private def updateActiveStyle(message: String): Unit =
      var index = 0
      while index < message.length do
        val escapeIndex = message.indexOf("\u001b[", index)
        if escapeIndex < 0 then index = message.length
        else
          val endIndex = message.indexOf("m", escapeIndex + 2)
          if endIndex < 0 then index = message.length
          else
            val code = message.substring(escapeIndex, endIndex + 1)
            if code == Console.RESET then activeStyle = ""
            else activeStyle = code
            index = endIndex + 1

    private def appendPrinted(chunk: String): Unit = this.synchronized {
      pendingOutput.append(chunk)
      flushCompletedLines()
      ps.flush()
    }

    private def flushToWriter(): Unit = this.synchronized {
      if pendingOutput.nonEmpty then
        val text = pendingOutput.toString
        printAboveClean(activeStyle + text)
        updateActiveStyle(text)
        pendingOutput.clear()
      ps.flush()
    }

    private def flushCompletedLines(): Unit =
      var newlineIndex = pendingOutput.indexOf("\n")
      while newlineIndex >= 0 do
        val line = pendingOutput.substring(0, newlineIndex + 1)
        printAboveClean(activeStyle + line)
        updateActiveStyle(line)
        pendingOutput.delete(0, newlineIndex + 1)
        newlineIndex = pendingOutput.indexOf("\n")

    def print(message: String): Unit = appendPrinted(message)
    def flush(): Unit = flushToWriter()
    override def setStatus(message: Option[String]): Unit = this.synchronized {
      statusLine = message
      redrawPrompt()
      ps.flush()
    }
