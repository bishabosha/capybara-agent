package capybara.agent

import org.jline.utils.AttributedString
import scala.jdk.CollectionConverters.*

class ScalaSyntaxHighlightSuite extends munit.FunSuite:
  private val ui = TerminalUi()

  test("Scalameta tokens colour Scala keywords, numbers, strings, comments and type names") {
    for code <- Vector("val", "42", "0xff", "3.14e-2", "\"hello\"", "'x'", "// comment", "List", "given", "enum") do
      val highlighted = ScalaSyntaxHighlight.highlight(code, ui)
      assertEquals(highlighted.toString, code)
      assert(highlighted.toAnsi.contains("\u001b["), s"No colour for $code")
  }

  test("nested comments, triple strings, interpolation, unicode and whitespace survive highlighting") {
    val examples = Vector(
      "/* outer\n/* nested */\nstill a comment */\nval x = 1",
      "val text = \"\"\"first\n// still a string\nlast\"\"\"",
      "val x = s\"界🦫 ${List(1, 2).size}\"\n\nputs(x)",
      "extension (x: Int)\n  def doubled = x * 2\n")
    for code <- examples do
      val highlighted = ScalaSyntaxHighlight.highlight(code, ui)
      assertEquals(highlighted.toString, code)
      assert(highlighted.toAnsi.contains("\u001b["))
    val commentLines = ScalaSyntaxHighlight.highlight(examples.head, ui).columnSplitLength(80).asScala
    assert(commentLines.take(3).forall(_.toAnsi.contains("\u001b[")))
  }

  test("plain themes, custom palettes, malformed snippets and control characters remain safe") {
    val code = "val n = 42\n"
    assertEquals(ScalaSyntaxHighlight.highlight(code, ui.copy(colorEnabled = false)).toAnsi, code)
    val custom = ui.copy(styles = Map(UiRole.CodeNumber -> TerminalStyle(TerminalColor.Blue)))
    assert(ScalaSyntaxHighlight.highlight("42", custom).toAnsi.contains("[34m"))
    val broken = "val x = \"unfinished"
    assertEquals(ScalaSyntaxHighlight.highlight(broken, ui).toString, broken)
    assertEquals(ScalaSyntaxHighlight.highlight("val\u007f x = \u001b[31m42\u001b[0m", ui).toString, "val x = 42")
  }

  test("panel wrapping and scrolling retain colour without changing code or output geometry") {
    val code = "/* long comment with 界🦫 text\nstill a comment */\n\nval answer = List(1, 2, 3)\nputs(answer)"
    val panels = TerminalPanels()
    val configured = ui.copy(panels = Vector(
      UiPanel("code", "Executions", Set(UiRole.Tool), content = UiPanelContent.ExecutionCode),
      UiPanel("output", "Output", Set(UiRole.ToolOutput), content = UiPanelContent.ExecutionOutput)))
    val execution = ExecutionView(Vector(ToolExecution(1, Vector.empty, code,
      output = Vector(ToolOutputChunk(ToolOutputChannel.Stdout, "val untouched = 42")))), Some(1), true)
    val navigation = PanelNavigation(Some("code"), Map("code" -> 2))
    val coloured = panels.renderNavigable(configured, 65, 30, TerminalUiContext(), execution, navigation)
    val plain = panels.renderNavigable(configured.copy(colorEnabled = false), 65, 30, TerminalUiContext(), execution, navigation)
    assertEquals(coloured.ranges, plain.ranges)
    assertEquals(coloured.lines.map(AttributedString.fromAnsi(_).toString), plain.lines)
    assert(coloured.lines.tail.exists(_.contains("\u001b[")))
    assert(coloured.lines.forall(line => AttributedString.fromAnsi(line).columnLength() <= 64))
    assert(coloured.lines.tail.forall(line => !line.split(" \\| ", 2).last.contains("\u001b[")))
  }
