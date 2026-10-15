package capybara.agent

import org.jline.reader.{LineReader, Reference}
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.{Size, Terminal}
import org.jline.utils.{AttributedString, AttributedStringBuilder, Display}
import org.jline.utils.InfoCmp.Capability
import org.jline.keymap.KeyMap
import scala.jdk.CollectionConverters.*

/** One display owner composes plugin panels, conversation and JLine's editable buffer. */
final class TerminalLineReader(terminal: Terminal) extends LineReaderImpl(terminal):
  private var viewport = Option.empty[TerminalViewport]
  private var screen: Display = null
  private var scrollOffset = 0
  private var panelNavigation = PanelNavigation()
  private var panelRanges = Map.empty[String, PanelRange]
  private var inputFocused = true
  private val executionSelections = new java.util.concurrent.ConcurrentLinkedQueue[ExecutionSelection]()

  // The logger drains this outside the editor lock; keyboard widgets never call it back.
  private[agent] def takeExecutionSelections(): Vector[ExecutionSelection] =
    val selected = Vector.newBuilder[ExecutionSelection]
    var next = executionSelections.poll()
    while next != null do
      selected += next
      next = executionSelections.poll()
    selected.result()

  private def navigateExecution(newer: Boolean): Boolean =
    if viewport.nonEmpty && !inputFocused then
      if panelNavigation.focus.nonEmpty then
        executionSelections.add(if newer then ExecutionSelection.Newer else ExecutionSelection.Older)
      else false
    else if newer then super.forwardChar() else super.backwardChar()

  private def focusable(view: TerminalViewport): Vector[String] =
    view.ui.panels.filter(p => p.content == UiPanelContent.ExecutionCode ||
      p.content == UiPanelContent.ExecutionOutput).map(_.id).filter(panelRanges.contains)

  def withDisplayLock[A](operation: => A): A =
    lock.lock()
    try operation
    finally lock.unlock()

  def setViewport(next: Option[TerminalViewport]): Unit = withDisplayLock {
    if next.nonEmpty && viewport.isEmpty then
      terminal.puts(Capability.enter_ca_mode)
      screen = new Display(terminal, true)
      screen.clear()
      scrollOffset = 0
    else if next.isEmpty && viewport.nonEmpty then
      terminal.puts(Capability.exit_ca_mode)
      screen = null
      scrollOffset = 0
      terminal.flush()
    if viewport.flatMap(_.panelSnapshot).flatMap(_.executions.selectedId) !=
        next.flatMap(_.panelSnapshot).flatMap(_.executions.selectedId) then
      panelNavigation = panelNavigation.copy(starts = Map.empty)
    if next.isEmpty then
      panelNavigation = PanelNavigation()
      inputFocused = true
      executionSelections.clear()
    else if panelNavigation.focus.exists(id => !next.get.ui.panels.exists(_.id == id)) then
      panelNavigation = panelNavigation.copy(focus = None)
      inputFocused = true
    viewport = next
    if viewport.nonEmpty then redisplay(true)
  }

  private def scroll(amount: Int, page: Boolean = true): Boolean =
    if viewport.nonEmpty then
      panelNavigation.focus.flatMap(panelRanges.get) match
        case Some(range) =>
          val id = panelNavigation.focus.get
          val distance = if page then amount.sign * range.page else amount
          val start = (range.start - distance).max(0).min(range.maximum)
          val output = viewport.get.ui.panels.exists(p => p.id == id && p.content == UiPanelContent.ExecutionOutput)
          val starts = if output && start == range.maximum then panelNavigation.starts - id
            else panelNavigation.starts.updated(id, start)
          panelNavigation = panelNavigation.copy(starts = starts)
        case None =>
          val maximum = viewport.get.transcript.length
          scrollOffset = (scrollOffset + amount).max(0).min(maximum)
      redisplay(true)
      true
    else if amount > 0 then super.upLineOrHistory()
    else super.downLineOrHistory()

  private def focusPanel(target: String): Boolean =
    viewport match
      case Some(view) =>
        val choices = focusable(view)
        val next: (Boolean, Option[String]) = target match
          case "cycle" =>
            val all = Vector((true, None)) ++ choices.map(id => (false, Some(id))) ++ Vector((false, None))
            all((all.indexOf((inputFocused, panelNavigation.focus)) + 1) % all.size)
          case "input" => (true, None)
          case "conversation" => (false, None)
          case kind => (false, view.ui.panels.find(p => choices.contains(p.id) &&
            (if kind == "code" then p.content == UiPanelContent.ExecutionCode else p.content == UiPanelContent.ExecutionOutput)).map(_.id))
        inputFocused = next._1
        panelNavigation = panelNavigation.copy(focus = next._2)
        redisplay(true)
        true
      case None => false

  private def panelBoundary(bottom: Boolean): Boolean =
    if viewport.isEmpty then false
    else
      panelNavigation.focus.flatMap(panelRanges.get) match
        case Some(range) =>
          val id = panelNavigation.focus.get
          panelNavigation = panelNavigation.copy(starts = if bottom then panelNavigation.starts - id
            else panelNavigation.starts.updated(id, 0))
          if bottom && viewport.get.ui.panels.exists(p => p.id == id && p.content == UiPanelContent.ExecutionCode) then
            panelNavigation = panelNavigation.copy(starts = panelNavigation.starts.updated(id, range.maximum))
        case None => scrollOffset = if bottom then 0 else viewport.get.transcript.length
      redisplay(true)
      true

  getWidgets.put("capybara-page-up", () => scroll((terminal.getHeight / 2).max(1)))
  getWidgets.put("capybara-page-down", () => scroll(-(terminal.getHeight / 2).max(1)))
  getWidgets.put("capybara-up", () =>
    if viewport.nonEmpty && !inputFocused then scroll(1, page = false) else super.upLineOrHistory())
  getWidgets.put("capybara-down", () =>
    if viewport.nonEmpty && !inputFocused then scroll(-1, page = false) else super.downLineOrHistory())
  getWidgets.put("capybara-previous-execution", () => navigateExecution(false))
  getWidgets.put("capybara-next-execution", () => navigateExecution(true))
  for target <- Vector("cycle", "input", "conversation", "code", "output") do
    getWidgets.put(s"capybara-focus-$target", () => focusPanel(target))
  getWidgets.put("capybara-top", () => panelBoundary(false))
  getWidgets.put("capybara-bottom", () => panelBoundary(true))
  for (capability, widget) <- Vector((Capability.key_ppage, "capybara-page-up"),
      (Capability.key_npage, "capybara-page-down"), (Capability.key_up, "capybara-up"),
      (Capability.key_down, "capybara-down"), (Capability.key_left, "capybara-previous-execution"),
      (Capability.key_right, "capybara-next-execution")) do
    val key = KeyMap.key(terminal, capability)
    if key != null then getKeyMaps.get(LineReader.MAIN).bind(new Reference(widget), key)

  // Shift-Tab cycles navigation focus. Arrow keys belong to the focused area.
  for (key, widget) <- Vector(
      ("\u0018p", "capybara-page-up"), ("\u0018n", "capybara-page-down"),
      ("\u0018\u0010", "capybara-page-up"), ("\u0018\u000e", "capybara-page-down"),
      ("\u0018f", "capybara-focus-cycle"), ("\u0018c", "capybara-focus-conversation"),
      ("\u0018e", "capybara-focus-code"), ("\u0018o", "capybara-focus-output"),
      ("\u0018t", "capybara-top"), ("\u0018b", "capybara-bottom"),
      ("\u001b[Z", "capybara-focus-cycle"), ("\u001b", "capybara-focus-input"),
      ("\u001b[A", "capybara-up"), ("\u001bOA", "capybara-up"),
      ("\u001b[B", "capybara-down"), ("\u001bOB", "capybara-down"),
      ("\u001b[D", "capybara-previous-execution"), ("\u001bOD", "capybara-previous-execution"),
      ("\u001b[C", "capybara-next-execution"), ("\u001bOC", "capybara-next-execution"),
      ("\u0010", "capybara-up"), ("\u000e", "capybara-down"),
      ("\u001b[5~", "capybara-page-up"), ("\u001b[6~", "capybara-page-down")) do
    getKeyMaps.get(LineReader.MAIN).bind(new Reference(widget), key)

  override protected def redisplay(flush: Boolean): Unit = withDisplayLock {
    viewport match
      case None => super.redisplay(flush)
      case Some(view) =>
        val columns = terminal.getWidth.max(2)
        val rows = terminal.getHeight.max(3)
        val width = columns - 1
        val panelRender = view.panelSnapshot.map(snapshot =>
          TerminalPanels.renderSnapshot(snapshot, view.ui, columns, rows, panelNavigation))
        panelRanges = panelRender.map(_.ranges).getOrElse(Map.empty)
        if panelNavigation.focus.exists(id => !panelRanges.contains(id)) then
          panelNavigation = panelNavigation.copy(focus = None)
          inputFocused = true
        panelNavigation = panelNavigation.copy(starts = panelNavigation.starts.flatMap { (id, _) =>
          panelRanges.get(id).map(range => id -> range.start)
        })
        val focusLabel = if inputFocused then "Input" else
          panelNavigation.focus.flatMap(id => view.ui.panels.find(_.id == id)).map(_.title).getOrElse("Conversation")
        val navigationHint = if inputFocused then "Focus: Input · ↑/↓: command history · Shift-Tab: switch focus"
          else if panelNavigation.focus.nonEmpty then s"Focus: $focusLabel · ↑/↓ scroll · ←/→ calls · Shift-Tab switch · Esc input"
          else s"Focus: $focusLabel · ↑/↓: scroll · Shift-Tab: switch · Esc: input"
        val navigableView = view.copy(panels = panelRender.map(_.lines).getOrElse(view.panels),
          status = Some(view.status.map(progress => s"Focus: $focusLabel · $progress · Shift-Tab: switch")
            .getOrElse(navigationHint)))
        val secondary = new java.util.ArrayList[AttributedString]()
        val editing = isReading
        val full = if editing then getDisplayedBufferWithPrompts(secondary) else prompt
        val editor = full.columnSplitLength(width).asScala.toVector
        val prefix = new AttributedStringBuilder().append(prompt)
        if editing then
          val text = if maskingCallback == null then buf.upToCursor() else maskingCallback.display(buf.upToCursor())
          val parts = text.split("\n", -1)
          parts.zipWithIndex.foreach { (part, index) =>
            if index > 0 then
              prefix.append("\n")
              if secondary.size() >= index then prefix.append(secondary.get(index - 1))
            prefix.append(part)
          }
        val beforeCursor = prefix.toAttributedString.columnSplitLength(width).asScala.toVector
        val cursorRow = (beforeCursor.size - 1).max(0)
        val cursorColumn = beforeCursor.lastOption.map(_.columnLength()).getOrElse(0)
        val withRight = editor.zipWithIndex.map { (line, index) =>
          val gap = width - line.columnLength() - rightPrompt.columnLength()
          if index == cursorRow && rightPrompt.length() > 0 && gap > 1 then
            new AttributedStringBuilder().append(line).append(" " * gap).append(rightPrompt).toAttributedString
          else line
        }
        val frame = TerminalFrame.render(navigableView, withRight, cursorRow, cursorColumn, columns, rows, scrollOffset)
        scrollOffset = scrollOffset.min(frame.maxScrollOffset)
        screen.resize(rows, columns)
        screen.update(new java.util.ArrayList[AttributedString](frame.lines.asJava), new Size(columns, rows).cursorPos(frame.cursorRow, frame.cursorColumn), flush)
  }

  override def redrawLine(): Boolean = withDisplayLock {
    if screen != null then screen.clear()
    super.redrawLine()
  }

  override protected def handleSignal(signal: Terminal.Signal): Unit = withDisplayLock {
    super.handleSignal(signal)
    if viewport.nonEmpty then
      screen.clear()
      redisplay(true)
  }

  override protected def doCleanup(newline: Boolean): Unit =
    if viewport.nonEmpty then
      scrollOffset = 0
      inputFocused = true
      panelNavigation = panelNavigation.copy(focus = None)
    super.doCleanup(if viewport.nonEmpty then false else newline)
