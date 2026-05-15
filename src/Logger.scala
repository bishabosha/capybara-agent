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
    private var transientStatus = Option.empty[String]
    private var transientStatusVisible = false

    private def printAboveClean(message: String): Unit =
      clearTransientStatus()
      reader.printAbove(resetWrapped(message))
      drawTransientStatus()

    override def prompt(basePrompt: String): String = this.synchronized {
      statusLine match
        case Some(message) => s"${Console.RESET}$message${Console.RESET}\n$basePrompt"
        case None          => basePrompt
    }

    private def promptText: String =
      statusLine match
        case Some(message) => s"${Console.RESET}$message${Console.RESET}\n$basePrompt"
        case None          => basePrompt

    private def redrawPrompt(): Unit =
      val nextPrompt = promptText
      reader match
        case impl: LineReaderImpl =>
          impl.setPrompt(nextPrompt)
          reader.callWidget(LineReader.REDISPLAY)
        case _ => ()

    private def setStoredPrompt(): Unit =
      val nextPrompt = statusLine match
        case Some(message) => s"${Console.RESET}$message${Console.RESET}\n$basePrompt"
        case None          => basePrompt
      reader match
        case impl: LineReaderImpl =>
          impl.setPrompt(nextPrompt)
        case _ => ()

    private def renderStatus(message: String): String =
      s"${Console.RESET}$message${Console.RESET}"

    private def clearTransientStatus(): Unit =
      if transientStatusVisible then
        ps.print("\r\u001b[2K")
        transientStatusVisible = false

    private def drawTransientStatus(): Unit =
      transientStatus.foreach { message =>
        ps.print(s"\r${renderStatus(message)}")
        transientStatusVisible = true
      }

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
      clearTransientStatus()
      val isReading = reader.isReading
      if isReading then
        transientStatus = None
        statusLine = message
        redrawPrompt()
      else
        statusLine = None
        transientStatus = message
        setStoredPrompt()
        drawTransientStatus()
      ps.flush()
    }
