package capybara.agent

import org.jline.utils.AttributedString
import scala.jdk.CollectionConverters.*

/** Immutable snapshot: the editor never calls back into the session/logger while drawing. */
final case class TerminalViewport(
    panels: Vector[String],
    transcript: String,
    status: Option[String],
    ui: TerminalUi,
    panelSnapshot: Option[PanelSnapshot] = None
)

final case class TerminalFrame(
    lines: Vector[AttributedString],
    cursorRow: Int,
    cursorColumn: Int,
    conversationStart: Int,
    editorStart: Int,
    maxScrollOffset: Int
)

object TerminalFrame:
  def render(view: TerminalViewport, editor: Vector[AttributedString], cursorRow: Int,
      cursorColumn: Int, columns: Int, rows: Int, scrollOffset: Int = 0): TerminalFrame =
    val width = (columns - 1).max(1)
    def clip(line: AttributedString): AttributedString = line.columnSubSequence(0, width)
    val divider = AttributedString.fromAnsi(view.ui.paint(UiRole.Muted, "─" * width))
    val top = view.panels.map(line => clip(AttributedString.fromAnsi(line)))
    val header = if top.isEmpty then Vector.empty else top :+ divider
    val maxEditorRows = (rows / 3).max(1)
    val editorWindow = (cursorRow - maxEditorRows + 1).max(0)
    val visibleEditor = editor.drop(editorWindow).take(maxEditorRows).map(clip) match
      case lines if lines.nonEmpty => lines
      case _ => Vector(AttributedString.EMPTY)
    val navigation = if scrollOffset > 0 then "History · Ctrl-X then N / Page Down: newer messages"
      else "Page Up/Down or Ctrl-X then P/N: scroll conversation"
    val statusText = if scrollOffset > 0 && view.panelSnapshot.isEmpty then navigation else view.status.getOrElse(navigation)
    val statusRole = if view.status.nonEmpty && scrollOffset == 0 then UiRole.Status else UiRole.Muted
    val status = if view.ui.showStatus then
      Vector(clip(AttributedString.fromAnsi(view.ui.paint(statusRole, statusText))))
    else Vector.empty
    val available = (rows - header.size - visibleEditor.size - status.size - 1).max(1)
    val history = AttributedString.fromAnsi(view.transcript.filterNot(_ == '\u007f')).columnSplitLength(width).asScala.toVector
      .reverse.dropWhile(_.length() == 0).reverse
    val maximumOffset = (history.size - available).max(0)
    val end = history.size - scrollOffset.min(maximumOffset).max(0)
    val conversation = history.take(end).takeRight(available).map(clip)
      .padTo(available, AttributedString.EMPTY)
    val body = header ++ conversation ++ Vector(divider) ++ status
    val lines = (body ++ visibleEditor).take(rows).padTo(rows, AttributedString.EMPTY)
    TerminalFrame(lines, (body.size + cursorRow - editorWindow).min(rows - 1),
      cursorColumn.min(width), header.size, body.size, maximumOffset)
