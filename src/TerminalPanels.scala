package capybara.agent

import org.jline.utils.AttributedString
import scala.jdk.CollectionConverters.*

final case class PanelNavigation(focus: Option[String] = None, starts: Map[String, Int] = Map.empty)
final case class PanelRange(start: Int, maximum: Int, page: Int)
final case class PanelRender(lines: Vector[String], ranges: Map[String, PanelRange])
final case class PanelSnapshot(streams: Vector[(UiRole, String)], context: TerminalUiContext, executions: ExecutionView)

object TerminalPanels:
  def renderSnapshot(snapshot: PanelSnapshot, ui: TerminalUi, columns: Int, rows: Int,
      navigation: PanelNavigation): PanelRender =
    val panels = new TerminalPanels()
    panels.chunks = snapshot.streams
    panels.renderNavigable(ui, columns, rows, snapshot.context, snapshot.executions, navigation)

/** Bounded streamed buffers and pure geometry shared by the terminal and layout tests. */
final class TerminalPanels(val capacity: Int = 16384):
  require(capacity > 0)
  private var chunks = Vector.empty[(UiRole, String)]

  def snapshot(context: TerminalUiContext, executions: ExecutionView): PanelSnapshot =
    PanelSnapshot(chunks, context, executions)

  def append(role: UiRole, text: String): Unit =
    val clean = TerminalUi.stripAnsi(text)
    if clean.nonEmpty then
      if chunks.lastOption.exists(_._1 == role) then
        chunks = chunks.init :+ (role, chunks.last._2 + clean)
      else chunks = chunks :+ (role, clean)
      chunks = chunks.takeRight(512)
      var remaining = capacity
      chunks = chunks.reverseIterator.flatMap { (kind, value) =>
        val kept = value.takeRight(remaining)
        remaining = (remaining - kept.length).max(0)
        Option.when(kept.nonEmpty)((kind, if kept.head.isLowSurrogate then kept.drop(1) else kept))
      }.toVector.reverse

  def clear(): Unit = chunks = Vector.empty

  def canRender(ui: TerminalUi, columns: Int, rows: Int): Boolean =
    ui.panels.nonEmpty && columns >= 12 && rows >= 10 &&
      (columns - 1 >= ui.panels.size * 24 || (rows / 3).min(ui.panelRows) >= ui.panels.size * 2)

  private def cleanLine(text: String): String =
    text.map(c => if c < ' ' || c == '\u007f' then ' ' else c)

  private def fit(text: String, width: Int): String =
    // JLine measures terminal columns, including wide CJK and supplementary characters.
    fit(new AttributedString(cleanLine(text)), width)

  private def fit(attributed: AttributedString, width: Int): String =
    val clipped = attributed.columnSubSequence(0, width)
    clipped.toAnsi + (" " * (width - clipped.columnLength()).max(0))

  private def panelLines(panel: UiPanel, width: Int, height: Int, ui: TerminalUi,
      context: TerminalUiContext, executions: ExecutionView, navigation: PanelNavigation): (Vector[String], PanelRange) =
    val selected = executions.selected
    val text = panel.content match
      case UiPanelContent.Streams => chunks.filter((role, _) => panel.roles(role)).map(_._2).mkString
      case UiPanelContent.ExecutionCode => selected.map(_.code).getOrElse("")
      case UiPanelContent.ExecutionOutput => selected.map(_.output.map(chunk =>
        s"[${chunk.channel.label}] ${chunk.text}").mkString).getOrElse("")
    val title = panel.content match
      case UiPanelContent.Streams => s"${panel.title} · ${context.state}"
      case _ => selected.map(record =>
        val name = if record.toolName == "run_scala_code" then "" else s" · ${record.toolName}"
        s"${panel.title} #${record.id} · ${record.state.toString.toLowerCase}$name")
        .getOrElse(panel.title + " · no executions")
    val lines = if panel.content == UiPanelContent.ExecutionCode && text.nonEmpty &&
        selected.exists(_.toolName == "run_scala_code") then
      ScalaSyntaxHighlight.highlight(text, ui).columnSplitLength(width).asScala.toVector
    else text.split("\n", -1).toVector.filter(_.nonEmpty).flatMap { line =>
      new AttributedString(cleanLine(line)).columnSplitLength(width).asScala
    }
    val content =
      if lines.nonEmpty then lines
      else if panel.roles == Set(UiRole.Thinking) && context.thinking == "off" then
        Vector(new AttributedString("Thinking is off; use /think on."))
      else if context.state == "running" then Vector(new AttributedString("Waiting for streamed output…"))
      else Vector(new AttributedString("No output yet."))
    val page = (height - 1).max(1)
    val maximum = (content.size - page).max(0)
    val default = if panel.content == UiPanelContent.ExecutionCode then 0 else maximum
    val start = navigation.starts.getOrElse(panel.id, default).max(0).min(maximum)
    val visible = content.slice(start, start + page)
    val position = if navigation.focus.contains(panel.id) then s" ${start + 1}-${(start + page).min(content.size)}/${content.size}" else ""
    val heading = if navigation.focus.contains(panel.id) then s"> ${panel.title}$position${title.stripPrefix(panel.title)}" else title
    (Vector(ui.paint(UiRole.Muted, fit(s"[ $heading ]", width))) ++
      visible.map(line => fit(line, width)).padTo(page, " " * width), PanelRange(start, maximum, page))

  def render(ui: TerminalUi, columns: Int, rows: Int,
      context: TerminalUiContext = TerminalUiContext(),
      executions: ExecutionView = ExecutionView(Vector.empty, None, true)): Vector[String] =
    renderNavigable(ui, columns, rows, context, executions, PanelNavigation()).lines

  def renderNavigable(ui: TerminalUi, columns: Int, rows: Int, context: TerminalUiContext,
      executions: ExecutionView, navigation: PanelNavigation): PanelRender =
    if !canRender(ui, columns, rows) then PanelRender(Vector.empty, Map.empty)
    else
      val width = columns - 1 // Avoid the terminal's last-column wrap behavior.
      val availableRows = (rows / 3).max(3).min(ui.panelRows)
      val gap = " | "
      if width < ui.panels.size * 24 then
        val height = availableRows / ui.panels.size
        val panes = ui.panels.map(panel => panel -> panelLines(panel, width, height, ui, context, executions, navigation))
        PanelRender(panes.flatMap(_._2._1), panes.map((panel, rendered) => panel.id -> rendered._2).toMap)
      else
        val contentWidth = width - gap.length * (ui.panels.size - 1)
        val totalWeight = ui.panels.map(_.weight).sum
        val allocated = ui.panels.dropRight(1).map(p => contentWidth * p.weight / totalWeight)
        val widths = allocated :+ (contentWidth - allocated.sum)
        val panes = ui.panels.zip(widths).map((panel, paneWidth) =>
          panel -> panelLines(panel, paneWidth, availableRows, ui, context, executions, navigation))
        PanelRender(Vector.tabulate(availableRows)(index => panes.map(_._2._1(index)).mkString(gap)),
          panes.map((panel, rendered) => panel.id -> rendered._2).toMap)
