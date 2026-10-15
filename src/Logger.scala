package capybara.agent

import java.io.PrintWriter
import org.jline.reader.LineReader
import org.jline.reader.impl.LineReaderImpl
import org.jline.utils.{AttributedString, Status}
import org.jline.utils.InfoCmp.Capability
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

trait Logger:
  def print(message: String): Unit
  def flush(): Unit
  def setStatus(message: Option[String]): Unit = ()
  def prompt(basePrompt: String): String = basePrompt
  def rightPrompt: String = ""
  def terminalUi: TerminalUi = TerminalUi()
  def setUi(ui: TerminalUi): Unit = ()
  def setUiContext(context: TerminalUiContext): Unit = ()
  def clearPanels(): Unit = ()
  def clearConversation(): Unit = clearPanels()
  def echoInput(line: String): Unit = ()
  def styled(role: UiRole, message: String): Unit = print(terminalUi.paint(role, message))
  def executionStarted(id: Long, universes: Seq[String], code: String): Unit =
    styled(UiRole.Tool, s"execution #$id [${universes.mkString(",")}]:\n```scala\n$code\n```\n")
  def executionRunning(id: Long): Unit = ()
  def executionOutput(id: Long, channel: ToolOutputChannel, text: String): Unit =
    val role = if channel == ToolOutputChannel.Stdout then UiRole.ToolOutput else UiRole.ToolError
    styled(role, s"[#$id ${channel.label}] $text")
  def executionFinished(id: Long, state: ToolExecutionState): Unit =
    styled(UiRole.Tool, s"execution #$id ${state.toString.toLowerCase}\n")
  def executionList: String = "Execution history is available in the interactive terminal."
  def selectExecution(selection: ExecutionSelection): Either[String, String] = Left("Execution history is unavailable.")
  def separator(): Unit =
    if terminalUi.separator.nonEmpty then styled(UiRole.Muted, terminalUi.separator + "\n")

object Logger:

  object ConsoleLogger extends Logger:
    def print(message: String): Unit = Console.err.print(message)
    def flush(): Unit = ()

  class TerminalLogger(reader: LineReader, ps: PrintWriter, basePrompt: String)
      extends Logger
      with AutoCloseable:
    def close(): Unit = this.synchronized {
      if !closed then
        closed = true
        val _ = repaintTask.cancel(false)
        val _ = ticker.shutdownNow()
        setStatus(None)
        flushToWriter()
        reader match
          case host: TerminalLineReader => host.setViewport(None)
          case _ => ()
        withDisplayLock { if panelStatus != null then panelStatus.close() }
    }

    private var closed = false
    private var ui = TerminalUi(prompt = basePrompt)
    private var uiContext = TerminalUiContext()
    private val panels = TerminalPanels()
    private val executions = ToolExecutionHistory()

    override def executionStarted(id: Long, universes: Seq[String], code: String): Unit = this.synchronized {
      executions.start(id, universes, code)
      super.executionStarted(id, universes, code)
      if hidesRole(UiRole.Tool) then
        val detail = TerminalUi.stripAnsi(s"tool #$id · run_scala_code [${universes.mkString(",")}] · $code")
          .map(c => if Character.isISOControl(c) then ' ' else c).replaceAll("\\s+", " ").trim
        val width = (reader.getTerminal.getWidth - 1).max(1)
        val preview = new AttributedString(detail).columnSubSequence(0, width).toString
        styled(UiRole.Muted, preview + "\n")
    }
    override def executionRunning(id: Long): Unit = this.synchronized {
      executions.state(id, ToolExecutionState.Running)
      panelsDirty = true
    }
    override def executionOutput(id: Long, channel: ToolOutputChannel, text: String): Unit = this.synchronized {
      executions.append(id, channel, text)
      super.executionOutput(id, channel, text)
    }
    override def executionFinished(id: Long, state: ToolExecutionState): Unit = this.synchronized {
      executions.state(id, state)
      super.executionFinished(id, state)
    }
    override def executionList: String = this.synchronized { executions.renderList }
    override def selectExecution(selection: ExecutionSelection): Either[String, String] = this.synchronized {
      val result = executions.select(selection)
      panelsDirty = true
      repaintPanels()
      result
    }
    private val panelStatus = Status.getStatus(reader.getTerminal)
    private var panelsFailed = false
    private var fullscreen = false
    private val transcript = new StringBuilder
    private val TranscriptCapacity = 65536

    private def remember(text: String): Unit =
      transcript.append(text.filterNot(_ == '\u007f'))
      if transcript.length > TranscriptCapacity then
        transcript.delete(0, transcript.length - TranscriptCapacity)
      panelsDirty = true

    override def echoInput(line: String): Unit = this.synchronized {
      if ui.layout == TerminalLayout.Fullscreen && line.trim.nonEmpty then
        remember(ui.paint(UiRole.Prompt, "> " + line + "\n"))
    }
    private val supportsPanels = panelStatus != null && Vector(Capability.change_scroll_region, Capability.save_cursor,
      Capability.restore_cursor, Capability.cursor_address).forall(cap => reader.getTerminal.getStringCapability(cap) != null)
    private var panelSize = (0, 0)
    private var panelsDirty = false
    private val ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
      new java.util.concurrent.ThreadFactory:
        def newThread(task: Runnable): Thread =
          val thread = new Thread(task, "capybara-ui-panels")
          thread.setDaemon(true)
          thread
    )

    override def terminalUi: TerminalUi = this.synchronized { ui }

    private def withDisplayLock[A](operation: => A): A = reader match
      case host: TerminalLineReader => host.withDisplayLock(operation)
      case _ => operation

    private def refreshPanels(): Unit = withDisplayLock {
      val size = (reader.getTerminal.getWidth, reader.getTerminal.getHeight)
      if supportsPanels && !panelsFailed && (panelsDirty || size != panelSize) then
        val top = panels.render(ui, size._1, size._2, uiContext, executions.snapshot)
        val wantsFullscreen = ui.layout == TerminalLayout.Fullscreen && size._1 >= 20 && size._2 >= 10 &&
          Vector(Capability.enter_ca_mode, Capability.exit_ca_mode, Capability.clear_screen)
            .forall(cap => reader.getTerminal.getStringCapability(cap) != null)
        reader match
          case host: TerminalLineReader if wantsFullscreen =>
            if !fullscreen then
              panelStatus.hide()
              panelStatus.close()
            fullscreen = true
            host.setViewport(Some(TerminalViewport(top, transcript.toString,
              if ui.showStatus then statusLine.orElse(transientStatus) else None, ui,
              Some(panels.snapshot(uiContext, executions.snapshot)))))
          case _ =>
            if fullscreen then
              reader.asInstanceOf[TerminalLineReader].setViewport(None)
              fullscreen = false
              if transcript.nonEmpty then reader.printAbove(transcript.toString)
              if ui.layout != TerminalLayout.Fullscreen then transcript.clear()
            panelStatus.resize()
            panelStatus.update(top.map(AttributedString.fromAnsi(_)).asJava)
        panelSize = size
        panelsDirty = false
    }

    private def repaintPanels(): Unit =
      try
        reader match
          case host: TerminalLineReader => host.takeExecutionSelections().foreach { selection =>
            val _ = executions.select(selection)
            val view = executions.snapshot
            if selection == ExecutionSelection.Newer && view.selectedId == view.records.lastOption.map(_.id) then
              val _ = executions.select(ExecutionSelection.Latest)
            panelsDirty = true
          }
          case _ => ()
        refreshPanels()
      catch case NonFatal(error) =>
        // ScheduledExecutor silently cancels periodic tasks after an exception.
        // Keep this task alive and make every stream visible if rendering fails.
        panelsFailed = true
        try withDisplayLock {
          reader match
            case host: TerminalLineReader => host.setViewport(None)
            case _ => ()
          fullscreen = false
          if panelStatus != null then panelStatus.hide()
        }
        catch case NonFatal(_) => ()
        appendPrinted(s"[UI panels unavailable: ${error.getClass.getSimpleName}: ${error.getMessage}; output continues in the transcript]\n")

    override def clearConversation(): Unit = this.synchronized {
      executions.clear()
      transcript.clear()
      panels.clear()
      panelsDirty = true
      repaintPanels()
    }

    override def clearPanels(): Unit = this.synchronized {
      panels.clear()
      panelsDirty = true
      repaintPanels()
    }

    override def setUi(next: TerminalUi): Unit = this.synchronized {
      next.validate()
      flushToWriter()
      clearTransientStatus()
      ui = next
      panelsFailed = false
      if !ui.showStatus then
        statusLine = None
        transientStatus = None
      panelsDirty = true
      repaintPanels()
      setStoredPrompt()
      if reader.isReading then redrawPrompt()
      else drawTransientStatus()
      ps.flush()
    }

    override def setUiContext(context: TerminalUiContext): Unit = this.synchronized {
      uiContext = context
      panelsDirty = true
      setStoredPrompt()
      if reader.isReading then redrawPrompt()
    }

    private def hidesRole(role: UiRole): Boolean =
      supportsPanels && !panelsFailed && ui.hiddenRoles(role) &&
        panels.canRender(ui, reader.getTerminal.getWidth, reader.getTerminal.getHeight)

    override def separator(): Unit = this.synchronized {
      if ui.layout != TerminalLayout.Fullscreen || !supportsPanels || panelsFailed ||
          !panels.canRender(ui, reader.getTerminal.getWidth, reader.getTerminal.getHeight) then
        super.separator()
    }

    override def styled(role: UiRole, message: String): Unit = this.synchronized {
      panels.append(role, message)
      panelsDirty = true
      // If panels cannot be displayed, keep every stream visible in the transcript.
      if !hidesRole(role) then appendPrinted(ui.paint(role, message))
    }

    private val pendingOutput = StringBuilder()
    private var activeStyle = ""
    private var statusLine = Option.empty[String]
    private var transientStatus = Option.empty[String]
    private var transientStatusVisible = false
    private val repaintTask = ticker.scheduleAtFixedRate(
      new Runnable:
        def run(): Unit = TerminalLogger.this.synchronized {
          if !closed then
            repaintPanels()
            ps.flush()
        },
      100L, 100L, java.util.concurrent.TimeUnit.MILLISECONDS
    )


    private def printAboveClean(message: String): Unit =
      clearTransientStatus()
      reader.printAbove(resetWrapped(message))
      drawTransientStatus()

    override def prompt(basePrompt: String): String = this.synchronized { promptText }

    override def rightPrompt: String = this.synchronized { ui.renderRightPrompt(uiContext) }

    private def promptText: String =
      val base = ui.renderPrompt(uiContext)
      if fullscreen then base
      else statusLine match
        case Some(message) => s"${renderStatus(message)}\n$base"
        case None => base

    private def redrawPrompt(): Unit =
      if fullscreen then repaintPanels()
      val nextPrompt = promptText
      reader match
        case impl: LineReaderImpl =>
          impl.setPrompt(nextPrompt)
          impl.setRightPrompt(ui.renderRightPrompt(uiContext))
          reader.callWidget(LineReader.REDISPLAY)
        case _ => ()

    private def setStoredPrompt(): Unit =
      val nextPrompt = promptText
      reader match
        case impl: LineReaderImpl =>
          impl.setPrompt(nextPrompt)
          impl.setRightPrompt(ui.renderRightPrompt(uiContext))
        case _ => ()

    private def renderStatus(message: String): String =
      ui.paint(UiRole.Status, TerminalUi.stripAnsi(message))

    private def clearTransientStatus(): Unit =
      if !fullscreen && transientStatusVisible then
        ps.print("\r\u001b[2K")
        transientStatusVisible = false

    private def drawTransientStatus(): Unit =
      if !fullscreen then transientStatus.foreach { message =>
        ps.print(s"\r${renderStatus(message)}")
        transientStatusVisible = true
      }

    private def resetWrapped(message: String): String =
      val lineEnding =
        if message.endsWith("\r\n") then "\r\n"
        else if message.endsWith("\n") then "\n"
        else ""
      if !ui.colorEnabled then TerminalUi.stripAnsi(message)
      else if lineEnding.nonEmpty then
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
      if fullscreen then remember(chunk)
      else
        if ui.layout == TerminalLayout.Fullscreen then remember(chunk)
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
    def flush(): Unit = this.synchronized {
      flushToWriter()
      repaintPanels()
    }
    override def setStatus(message: Option[String]): Unit = this.synchronized {
      clearTransientStatus()
      panelsDirty = true
      val shown = if ui.showStatus then message else None
      val isReading = reader.isReading
      if isReading then
        transientStatus = None
        statusLine = shown
        redrawPrompt()
      else
        statusLine = None
        transientStatus = shown
        setStoredPrompt()
        drawTransientStatus()
      ps.flush()
    }
