package capybara.agent

import org.jline.utils.AttributedString

class TerminalFrameSuite extends munit.FunSuite:
  private val ui = TerminalUi(colorEnabled = false, layout = TerminalLayout.Fullscreen)
  private def text(lines: Vector[AttributedString]): Vector[String] = lines.map(_.toString)

  test("panels are at the top, conversation in the middle, editor on the final row") {
    val view = TerminalViewport(Vector("Thinking | Tools", "live thought | live tool"),
      "user question\nassistant answer\n", Some("running"), ui)
    val frame = TerminalFrame.render(view, Vector(new AttributedString("> draft")), 0, 7, 100, 30)
    assertEquals(frame.lines.size, 30)
    assertEquals(text(frame.lines).head, "Thinking | Tools")
    assertEquals(text(frame.lines)(frame.conversationStart), "user question")
    assertEquals(text(frame.lines).last, "> draft")
    assertEquals(frame.cursorRow, 29)
    assertEquals(frame.cursorColumn, 7)
    assertEquals(text(frame.lines)(28), "running")
    assert(frame.conversationStart < frame.editorStart)
  }

  test("conversation scrolls without moving the panels or bottom composer") {
    val history = (1 to 100).map(n => s"message $n").mkString("\n")
    val view = TerminalViewport(Vector("top panels"), history, None, ui)
    val editor = Vector(new AttributedString("> "))
    val latest = TerminalFrame.render(view, editor, 0, 2, 80, 24)
    val earlier = TerminalFrame.render(view, editor, 0, 2, 80, 24, 10000)
    assert(text(latest.lines).contains("message 100"))
    assertEquals(text(earlier.lines)(earlier.conversationStart), "message 1")
    assertEquals(latest.cursorRow, 23)
    assertEquals(earlier.cursorRow, 23)
    assertEquals(text(latest.lines).head, text(earlier.lines).head)
  }

  test("DEL control characters never become visible transcript glyphs") {
    val view = TerminalViewport(Vector("panels"), "tool #1\n\u007f\nanswer\u007f\n", None, ui)
    val frame = TerminalFrame.render(view, Vector(new AttributedString("> ")), 0, 2, 80, 24)
    assert(!text(frame.lines).mkString.contains('\u007f'))
    assert(text(frame.lines).contains("answer"))
  }

  test("multiline editor follows its cursor and reserves space for the conversation") {
    val view = TerminalViewport(Vector("panels"), "conversation", None, ui)
    val editor = (1 to 20).map(n => new AttributedString(s"edit $n")).toVector
    val frame = TerminalFrame.render(view, editor, 19, 4, 50, 15)
    assertEquals(frame.cursorRow, 14)
    assertEquals(text(frame.lines).last, "edit 20")
    assert(text(frame.lines).contains("conversation"))
    assert(frame.editorStart >= 10)
  }

  test("resizing and wide Unicode never overrun the frame") {
    val view = TerminalViewport(Vector("Thinking 界🦫" * 10), "界🦫" * 60, Some("status"), ui)
    for (columns, rows) <- Seq((100, 30), (35, 15), (20, 10)) do
      val frame = TerminalFrame.render(view, Vector(new AttributedString("> ")), 0, 2, columns, rows)
      assertEquals(frame.lines.size, rows)
      assert(frame.lines.forall(_.columnLength() < columns))
      assertEquals(frame.cursorRow, rows - 1)
  }
