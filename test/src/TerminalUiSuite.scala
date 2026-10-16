package capybara.agent

import org.jline.utils.AttributedString

class TerminalUiSuite extends munit.FunSuite:
  private val thinking = UiPanel("thinking", "Thinking", Set(UiRole.Thinking), weight = 2)
  private val tools = UiPanel("tools", "Tools", Set(UiRole.Tool, UiRole.ToolOutput, UiRole.ToolError))
  private val split = TerminalUi(colorEnabled = false, panels = Vector(thinking, tools))

  test("UI plugins compose in load order and invalid live updates preserve the installed UI") {
    var invalid = false
    val first = new HarnessPlugin:
      val id = "first"
      val description = "first"
      override def configureUi(current: TerminalUi): TerminalUi = current.copy(prompt = "capy> ")
    val second = new HarnessPlugin:
      val id = "second"
      val description = "second"
      override def configureUi(current: TerminalUi): TerminalUi =
        current.copy(prompt = if invalid then "" else current.prompt + "λ ", rightPrompt = "{state}")
    val runtime = HarnessPlugins(Vector(first, second))
    try
      assertEquals(runtime.ui.prompt, "capy> λ ")
      invalid = true
      assert(runtime.refreshUi().left.toOption.get.contains("plugin second"))
      assertEquals(runtime.ui.prompt, "capy> λ ")
    finally runtime.close()
  }

  test("prompt widgets expand current session data without recursively expanding workspace text") {
    val context = TerminalUiContext("/tmp/project-{state}-$demo", 3, "on", "running")
    val ui = TerminalUi(colorEnabled = false, prompt = "{workspace}> ",
      promptHeader = "{cwd}", rightPrompt = "{state} · {turns} · {thinking}")
    assertEquals(ui.renderPrompt(context), "/tmp/project-{state}-$demo\nproject-{state}-$demo> ")
    assertEquals(ui.renderRightPrompt(context), "running · 3 · on")
  }

  test("semantic palette applies to assistant, thinking and tool output; plain mode removes ANSI") {
    val ui = TerminalUi(styles = Map(UiRole.Assistant -> TerminalStyle(TerminalColor.Magenta, bold = true)))
    assert(ui.paint(UiRole.Assistant, "answer").startsWith("\u001b[35;1m"))
    assertEquals(ui.copy(colorEnabled = false).paint(UiRole.Assistant, "\u001b[31manswer\u001b[0m"), "answer")
  }

  test("split band renders two panes at wide widths and stacks both at narrow widths") {
    val buffers = TerminalPanels()
    buffers.append(UiRole.Thinking, "Considering cancellation\n")
    buffers.append(UiRole.Tool, "run_scala_code[editing]\n")
    buffers.append(UiRole.ToolOutput, "done\n")
    val wide = buffers.render(split, 100, 30)
    assert(wide.head.contains("Thinking") && wide.head.contains("Tools"))
    assert(wide.mkString.contains("Considering cancellation"))
    val narrow = buffers.render(split, 35, 30)
    assert(narrow.exists(_.contains("Thinking")))
    assert(narrow.exists(_.contains("Tools")))
    for (lines, width) <- Seq((wide, 100), (narrow, 35)) do
      assert(lines.size <= split.panelRows)
      assert(lines.forall(line => new AttributedString(line).columnLength() <= width - 1))
    assertEquals(buffers.render(split, 10, 8), Vector.empty)
    assert(!buffers.canRender(split, 35, 10))
  }

  test("live buffers preserve tool/output ordering and wrap recent Unicode content by display width") {
    val buffers = TerminalPanels()
    buffers.append(UiRole.Tool, "first call\n")
    buffers.append(UiRole.ToolOutput, "first result\n")
    buffers.append(UiRole.Tool, "second call\n")
    val one = split.copy(panels = Vector(tools))
    val rendered = buffers.render(one, 40, 30).map(_.trim)
    assert(rendered.indexOf("first call") < rendered.indexOf("first result"))
    assert(rendered.indexOf("first result") < rendered.indexOf("second call"))
    buffers.clear()
    buffers.append(UiRole.Tool, "界🦫" * 40 + "LATEST")
    val unicode = buffers.render(one, 30, 30)
    assert(unicode.mkString.contains("LATEST"))
    assert(unicode.forall(line => new AttributedString(line).columnLength() <= 29))
  }

  test("buffers are bounded and can be cleared without leaking old turn content") {
    val buffers = TerminalPanels(capacity = 20)
    buffers.append(UiRole.Thinking, "discard this old content\n")
    buffers.append(UiRole.Thinking, "latest\n")
    val before = buffers.render(split, 100, 30).mkString
    assert(!before.contains("discard"))
    assert(before.contains("latest"))
    buffers.clear()
    assert(!buffers.render(split, 100, 30).mkString.contains("latest"))
  }

  test("execution and output panels scroll independently and output follows unless pinned") {
    val ui = TerminalUi(colorEnabled = false, panels = Vector(
      UiPanel("code", "Executions", Set(UiRole.Tool), content = UiPanelContent.ExecutionCode),
      UiPanel("output", "Output", Set(UiRole.ToolOutput), content = UiPanelContent.ExecutionOutput)))
    val buffers = TerminalPanels()
    def execution(count: Int) = ExecutionView(Vector(ToolExecution(1, Vector("basic"),
      (1 to 30).map(n => s"CODE-$n").mkString("\n"), output = Vector(ToolOutputChunk(ToolOutputChannel.Stdout,
        (1 to count).map(n => s"OUT-$n").mkString("\n"))))), Some(1), true)
    val initial = buffers.renderNavigable(ui, 100, 30, TerminalUiContext(), execution(30), PanelNavigation())
    assertEquals(initial.ranges("code").start, 0)
    assertEquals(initial.ranges("output").start, 25)
    val navigation = PanelNavigation(Some("code"), Map("code" -> 10, "output" -> 3))
    val scrolled = buffers.renderNavigable(ui, 100, 30, TerminalUiContext(), execution(35), navigation)
    assertEquals(scrolled.ranges("code").start, 10)
    assertEquals(scrolled.ranges("output").start, 3)
    assert(scrolled.lines.head.contains("> Executions"))
    assert(scrolled.lines(1).contains("CODE-11"))
    assert(scrolled.lines(1).contains("OUT-4"))
    val following = buffers.renderNavigable(ui, 100, 30, TerminalUiContext(), execution(35),
      navigation.copy(starts = navigation.starts - "output"))
    assertEquals(following.ranges("output").start, 30)
    val resized = buffers.renderNavigable(ui, 35, 30, TerminalUiContext(), execution(35),
      PanelNavigation(Some("output"), Map("output" -> 999)))
    assertEquals(resized.ranges("output").start, resized.ranges("output").maximum)
    assert(resized.lines.forall(line => new AttributedString(line).columnLength() < 35))
    assertEquals(TerminalPanels.renderSnapshot(buffers.snapshot(TerminalUiContext(), execution(30)),
      ui, 100, 30, PanelNavigation()).lines, initial.lines)
  }

  test("invalid geometry and hidden output without panels are rejected") {
    val _ = intercept[IllegalArgumentException](split.copy(panelRows = 1).validate())
    val _ = intercept[IllegalArgumentException](split.copy(panels = Vector(thinking, thinking)).validate())
    val _ = intercept[IllegalArgumentException](split.copy(rightPrompt = "two\nlines").validate())
    val _ = intercept[IllegalArgumentException](TerminalUi(hiddenRoles = Set(UiRole.Thinking)).validate())
  }

  test("source UI plugins compile, change themes and toggle layouts with existing plugin commands") {
    val runtime = HarnessPlugins.load(Some(os.pwd / "examples/plugins")) match
      case Right(value) => value
      case Left(error) => fail(error)
    val context = PluginContext(os.pwd, Vector.empty)
    try
      assertEquals(runtime.ui.name, "aurora")
      assertEquals(runtime.ui.panels.map(_.id), Vector("thinking", "executions", "output"))
      assert(runtime.ui.hiddenRoles(UiRole.Thinking))
      assert(runtime.ui.hiddenRoles(UiRole.Tool))
      assert(runtime.ui.hiddenRoles(UiRole.ToolOutput))
      assert(runtime.ui.hiddenRoles(UiRole.ToolError))
      val _ = runtime.dispatch("/split-thinking", context)
      assert(!runtime.refreshUi().toOption.get.hiddenRoles(UiRole.Thinking))
      val _ = runtime.dispatch("/split-thinking", context)
      assert(runtime.refreshUi().toOption.get.hiddenRoles(UiRole.Thinking))
      val _ = runtime.dispatch("/split-response", context)
      assert(!runtime.refreshUi().toOption.get.hiddenRoles(UiRole.Tool))
      val _ = runtime.dispatch("/split-response", context)
      val restored = runtime.refreshUi().toOption.get
      assert(restored.hiddenRoles(UiRole.Thinking))
      assert(restored.hiddenRoles(UiRole.Tool))
      val _ = runtime.dispatch("/split-tools", context)
      val withoutTools = runtime.refreshUi().toOption.get
      assert(!withoutTools.hiddenRoles(UiRole.Tool))
      assert(!withoutTools.hiddenRoles(UiRole.ToolOutput))
      assert(!withoutTools.hiddenRoles(UiRole.ToolError))
      val _ = runtime.dispatch("/split-tools", context)
      assert(runtime.refreshUi().toOption.get.hiddenRoles(UiRole.ToolOutput))
      val _ = runtime.dispatch("/theme plain", context)
      assert(!runtime.refreshUi().toOption.get.colorEnabled)
      val _ = runtime.dispatch("/split", context)
      assertEquals(runtime.refreshUi().toOption.get.panels, Vector.empty)
      assertEquals(runtime.ui.hiddenRoles, Set.empty)
    finally runtime.close()
  }

  test("native logger renders prompt widgets, plain output and hidden-status mode on a dumb terminal") {
    val bytes = new java.io.ByteArrayOutputStream()
    val terminal = new org.jline.terminal.impl.DumbTerminal(
      new java.io.ByteArrayInputStream(Array.emptyByteArray), bytes)
    val reader = new TerminalLineReader(terminal)
    val logger = new Logger.TerminalLogger(reader, terminal.writer(), "> ")
    try
      logger.setUi(split.copy(prompt = "{workspace}> ", rightPrompt = "{state}/{turns}",
        showStatus = false, hiddenRoles = Set(UiRole.Thinking)))
      logger.setUiContext(TerminalUiContext("/tmp/demo", 2, "on", "running"))
      logger.setStatus(Some("invisible-status"))
      assertEquals(logger.prompt("ignored"), "demo> ")
      assertEquals(logger.rightPrompt, "running/2")
      logger.styled(UiRole.Thinking, "kept on unsupported terminal\n")
      logger.flush()
      val output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
      assert(output.contains("kept on unsupported terminal"))
      assert(!output.contains("invisible-status"))
      assert(!output.contains("\u001b["))
      logger.close()
    finally
      logger.close()
      terminal.close()
  }

  test("native JLine status area displays split panels and releases reserved rows when disabled") {
    val bytes = new java.io.ByteArrayOutputStream()
    val terminal = new org.jline.terminal.impl.DumbTerminal("ui-test", "xterm",
      new java.io.ByteArrayInputStream(Array.emptyByteArray), bytes, java.nio.charset.StandardCharsets.UTF_8)
    terminal.setSize(new org.jline.terminal.Size(100, 30))
    val reader = new TerminalLineReader(terminal)
    val logger = new Logger.TerminalLogger(reader, terminal.writer(), "> ")
    try
      logger.setUi(split)
      logger.styled(UiRole.Thinking, "live reasoning\n")
      logger.styled(UiRole.Tool, "live tool\n")
      logger.flush()
      assertEquals(org.jline.utils.Status.getStatus(terminal).size(), 6)
      val output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
      assert(output.contains("Thinking"))
      assert(output.contains("Tools"))
      terminal.setSize(new org.jline.terminal.Size(35, 30))
      logger.flush()
      assertEquals(org.jline.utils.Status.getStatus(terminal).size(), 6)
      logger.setUi(TerminalUi())
      assertEquals(org.jline.utils.Status.getStatus(terminal).size(), 0)
    finally
      logger.close()
      terminal.close()
  }

  test("panels repaint streamed chunks while the JLine editor is waiting for input") {
    val bytes = new java.io.ByteArrayOutputStream()
    val input = new java.io.PipedInputStream()
    val keyboard = new java.io.PipedOutputStream(input)
    val terminal = new org.jline.terminal.impl.DumbTerminal("live-ui", "xterm", input, bytes,
      java.nio.charset.StandardCharsets.UTF_8)
    terminal.setSize(new org.jline.terminal.Size(100, 30))
    val reader = new TerminalLineReader(terminal)
    val logger = new Logger.TerminalLogger(reader, terminal.writer(), "> ")
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    def waitFor(condition: => Boolean): Unit =
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
      while !condition && System.nanoTime() < deadline do Thread.sleep(10)
      assert(condition)
    try
      logger.setUi(split.copy(hiddenRoles = Set(UiRole.Thinking, UiRole.Tool)))
      val reading = executor.submit(new java.util.concurrent.Callable[String]:
        def call(): String = reader.readLine(logger.prompt("> "))
      )
      waitFor(reader.isReading())
      bytes.reset()
      logger.styled(UiRole.Thinking, "FIRST-LIVE-MARKER")
      waitFor(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("FIRST-LIVE-MARKER"))
      bytes.reset()
      logger.styled(UiRole.Tool, "TOOL-LIVE-MARKER\n")
      waitFor(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("TOOL-LIVE-MARKER"))
      // Model output, progress updates and editing all repaint the same terminal.
      for index <- 1 to 300 do
        keyboard.write('a')
        keyboard.flush()
        logger.setStatus(Some(s"waiting $index"))
        logger.styled(UiRole.Thinking, s"reason $index\n")
        logger.styled(UiRole.Tool, s"tool $index\n")
      bytes.reset()
      logger.styled(UiRole.Tool, "FINAL-REPAINT-MARKER\n")
      waitFor(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("FINAL-REPAINT-MARKER"))
      keyboard.write("finished\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      keyboard.flush()
      assertEquals(reading.get(3, java.util.concurrent.TimeUnit.SECONDS), "a" * 300 + "finished")
    finally
      keyboard.close()
      val _ = executor.shutdownNow()
      logger.close()
      terminal.close()
  }

  test("a failed asynchronous repaint falls back visibly and can be recovered without restarting") {
    val bytes = new java.io.ByteArrayOutputStream()
    val failNext = new java.util.concurrent.atomic.AtomicBoolean(false)
    val terminal = new org.jline.terminal.impl.DumbTerminal("repaint-failure", "xterm",
      new java.io.ByteArrayInputStream(Array.emptyByteArray), bytes, java.nio.charset.StandardCharsets.UTF_8):
      private lazy val testStatus = new org.jline.utils.Status(this):
        override def update(lines: java.util.List[AttributedString]): Unit =
          if failNext.getAndSet(false) then throw IllegalStateException("simulated repaint failure")
          super.update(lines)
      override def getStatus(create: Boolean): org.jline.utils.Status = testStatus
    terminal.setSize(new org.jline.terminal.Size(100, 30))
    val reader = new TerminalLineReader(terminal)
    val logger = new Logger.TerminalLogger(reader, terminal.writer(), "> ")
    def waitFor(condition: => Boolean): Unit =
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
      while !condition && System.nanoTime() < deadline do Thread.sleep(10)
      assert(condition)
    try
      logger.setUi(split.copy(hiddenRoles = Set(UiRole.Thinking)))
      failNext.set(true)
      logger.styled(UiRole.Thinking, "first stream\n")
      waitFor(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("UI panels unavailable"))
      logger.styled(UiRole.Thinking, "VISIBLE-AFTER-FAILURE\n")
      logger.flush()
      assert(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("VISIBLE-AFTER-FAILURE"))
      logger.setUi(split)
      assertEquals(org.jline.utils.Status.getStatus(terminal).size(), 6)
      bytes.reset()
      logger.styled(UiRole.Tool, "REPAINT-RECOVERED\n")
      waitFor(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("REPAINT-RECOVERED"))
    finally
      logger.close()
      terminal.close()
  }

  test("empty panels identify running state and explain disabled thinking") {
    val buffers = TerminalPanels()
    val context = TerminalUiContext(thinking = "off", state = "running")
    val lines = buffers.render(split, 100, 30, context).mkString("\n")
    assert(lines.contains("Thinking · running"))
    assert(lines.contains("Thinking is off"))
    assert(lines.contains("Waiting for streamed output"))
  }
