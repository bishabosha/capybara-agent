package capybara.agent

class ToolExecutionsSuite extends munit.FunSuite:
  test("selection keeps code, stream channels and state from the same execution") {
    val history = ToolExecutionHistory()
    history.start(1, Seq("basic"), "puts(1)")
    history.append(1, ToolOutputChannel.Stdout, "one\n")
    history.append(1, ToolOutputChannel.Stderr, "warning\n")
    history.state(1, ToolExecutionState.Succeeded)
    history.start(2, Seq("basic"), "puts(2)")
    assertEquals(history.snapshot.selected.map(_.id), Some(2L))
    assert(history.select(ExecutionSelection.Older).isRight)
    history.start(3, Seq("basic"), "puts(3)")
    val selected = history.snapshot.selected.get
    assertEquals(selected.id, 1L)
    assertEquals(selected.code, "puts(1)")
    assertEquals(selected.state, ToolExecutionState.Succeeded)
    assertEquals(selected.output.map(_.channel), Vector(ToolOutputChannel.Stdout, ToolOutputChannel.Stderr))
    assert(!history.snapshot.following)
    assert(history.select(ExecutionSelection.ById(99)).isLeft)
    assert(history.select(ExecutionSelection.Latest).isRight)
    assertEquals(history.snapshot.selected.map(_.id), Some(3L))
    assert(history.snapshot.following)
    assert(history.renderList.contains("#1 succeeded [basic] puts(1)"))
  }

  test("history evicts old executions and bounds per-call output, including Unicode") {
    val history = ToolExecutionHistory(limit = 2, textLimit = 8)
    history.start(1, Nil, "1")
    val _ = history.select(ExecutionSelection.ById(1))
    history.start(2, Nil, "2")
    history.start(3, Nil, "3")
    history.append(3, ToolOutputChannel.Stdout, "discard😀abcdefg")
    assertEquals(history.snapshot.records.map(_.id), Vector(2L, 3L))
    assert(history.snapshot.following)
    val output = history.snapshot.selected.get.output.map(_.text).mkString
    assert(output.length <= 8)
    assert(!output.head.isLowSurrogate)
    history.state(3, ToolExecutionState.Cancelled)
    assertEquals(history.snapshot.selected.get.state, ToolExecutionState.Cancelled)
    history.clear()
    assertEquals(history.snapshot.records, Vector.empty)
  }

  test("execution panes show selected code and matching stdout and stderr") {
    val history = ToolExecutionHistory()
    history.start(1, Seq("basic"), "puts(1)")
    history.append(1, ToolOutputChannel.Stdout, "FIRST\n")
    history.append(1, ToolOutputChannel.Stderr, "WARNING\n")
    history.start(2, Seq("basic"), "puts(2)")
    history.append(2, ToolOutputChannel.Stdout, "SECOND\n")
    val _ = history.select(ExecutionSelection.Older)
    val ui = TerminalUi(colorEnabled = false, panels = Vector(
      UiPanel("executions", "Executions", Set(UiRole.Tool), content = UiPanelContent.ExecutionCode),
      UiPanel("output", "Output", Set(UiRole.ToolOutput), content = UiPanelContent.ExecutionOutput)))
    val rendered = TerminalPanels().render(ui, 110, 32, executions = history.snapshot).mkString("\n")
    assert(rendered.contains("puts(1)"))
    assert(rendered.contains("[stdout] FIRST"))
    assert(rendered.contains("[stderr] WARNING"))
    assert(!rendered.contains("SECOND"))
  }
