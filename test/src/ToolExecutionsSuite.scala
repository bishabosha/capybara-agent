package capybara.agent

class ToolExecutionsSuite extends munit.FunSuite:
  test("discovery calls appear in the default conversation and split-view execution history") {
    val split = TerminalUi(colorEnabled = false, panels = Vector(
      UiPanel("executions", "Executions", Set(UiRole.Tool), content = UiPanelContent.ExecutionCode),
      UiPanel("output", "Output", Set(UiRole.ToolOutput, UiRole.ToolError), content = UiPanelContent.ExecutionOutput)),
      hiddenRoles = Set(UiRole.Tool, UiRole.ToolOutput, UiRole.ToolError))
    for ui <- Seq(TerminalUi(colorEnabled = false), split) do
      val bytes = new java.io.ByteArrayOutputStream()
      val terminal = new org.jline.terminal.impl.DumbTerminal("discovery-ui", "xterm",
        new java.io.ByteArrayInputStream(Array.emptyByteArray), bytes, java.nio.charset.StandardCharsets.UTF_8)
      terminal.setSize(new org.jline.terminal.Size(180, 32))
      val logger = new Logger.TerminalLogger(new TerminalLineReader(terminal), terminal.writer(), "> ")
      try
        logger.setUi(ui)
        val names = ScalaAgent.SkillNames(Seq("filesystem"))
        assertEquals(ScalaAgent.runDiscovery("describe_skills", names, logger),
          ScalaAgent.discoveryResult("describe_skills", names))
        logger.flush()
        assert(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("describe_skills"))
        val success = logger.executionSnapshot.selected.get
        assertEquals(success.toolName, "describe_skills")
        assertEquals(success.state, ToolExecutionState.Succeeded)
        assertEquals(ujson.read(success.code)("universes")(0).str, "filesystem")
        assert(success.output.exists(chunk => chunk.channel == ToolOutputChannel.Stdout && chunk.text.contains("filesystem:")))
        assert(logger.executionList.contains("describe_skills"))
        if ui.panels.nonEmpty then
          val panes = TerminalPanels().render(ui, 180, 32, executions = logger.executionSnapshot).mkString("\n")
          assert(panes.contains("describe_skills"))
          assert(panes.contains("universes"))
          assert(panes.contains("filesystem:"))
        val _ = ScalaAgent.runDiscovery("read_skills", ScalaAgent.SkillNames(Seq("missing")), logger)
        val failed = logger.executionSnapshot.selected.get
        assertEquals(failed.toolName, "read_skills")
        assertEquals(failed.state, ToolExecutionState.Failed)
        assert(failed.output.exists(chunk => chunk.channel == ToolOutputChannel.Stderr && chunk.text.contains("Universe not found")))
        val json = SessionExport.snapshot(Vector.empty, "system", os.pwd, "auto", 0, None, logger.executionSnapshot)
        assertEquals(json("retainedToolCalls")(0)("name").str, "describe_skills")
        assertEquals(json("retainedToolCalls")(1)("state").str, "failed")
      finally
        logger.close()
        terminal.close()
  }

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
