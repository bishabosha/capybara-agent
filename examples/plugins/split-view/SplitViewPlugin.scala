package example

import capybara.agent.*

/** Inspired by pi-split-view; top panels, conversation, and a fixed bottom editor. */
final class SplitViewPlugin extends HarnessPlugin:
  val id = "split-view"
  val description = "Live thinking, tool-call history, and separate stdout/stderr output"
  private var enabled = true
  private var thinking = true
  private var tools = true
  private var responseOnly = true
  private var height = 6

  override val commands = Seq(
    PluginCommand("executions", "List retained tool calls", (_, _) => PluginAction.ListExecutions),
    PluginCommand("execution", "Inspect tool input and output: /execution <id|older|newer|latest>", (args, _) =>
      args.trim.toLowerCase match
        case "older" => PluginAction.InspectExecution(ExecutionSelection.Older)
        case "newer" => PluginAction.InspectExecution(ExecutionSelection.Newer)
        case "latest" | "" => PluginAction.InspectExecution(ExecutionSelection.Latest)
        case value => value.stripPrefix("#").toLongOption match
          case Some(id) => PluginAction.InspectExecution(ExecutionSelection.ById(id))
          case None => PluginAction.Message("usage: /execution <id|older|newer|latest>")),
    PluginCommand("split", "Toggle the thinking/execution/output band", (_, _) =>
      enabled = !enabled
      PluginAction.Message(s"Split view ${if enabled then "on" else "off"}.")),
    PluginCommand("split-thinking", "Toggle the thinking panel", (_, _) =>
      thinking = !thinking
      PluginAction.Message(s"Thinking panel ${if thinking then "on" else "off"}.")),
    PluginCommand("split-tools", "Toggle execution and output panels", (_, _) =>
      tools = !tools
      PluginAction.Message(s"Tools panel ${if tools then "on" else "off"}.")),
    PluginCommand("split-response", "Toggle response-only transcript (panel content stays live)", (_, _) =>
      responseOnly = !responseOnly
      PluginAction.Message(s"Response-only transcript ${if responseOnly then "on" else "off"}.")),
    PluginCommand("split-height", "Set the panel band height: /split-height 3..20", (args, _) =>
      args.toIntOption.filter(n => n >= 3 && n <= 20) match
        case Some(rows) =>
          height = rows
          PluginAction.Message(s"Panel band height: $rows.")
        case None => PluginAction.Message("usage: /split-height <3..20>"))
  )

  override def configureUi(current: TerminalUi): TerminalUi =
    val own =
      if !enabled then Vector.empty
      else Vector.empty ++
        Option.when(thinking)(UiPanel("thinking", "Thinking", Set(UiRole.Thinking), weight = 1)) ++
        (if tools then Vector(
          UiPanel("executions", "Executions", Set(UiRole.Tool), weight = 2, content = UiPanelContent.ExecutionCode),
          UiPanel("output", "Output", Set(UiRole.ToolOutput, UiRole.ToolError), weight = 2,
            content = UiPanelContent.ExecutionOutput)) else Vector.empty)
    current.copy(
      panels = current.panels ++ own,
      panelRows = height,
      layout = TerminalLayout.Fullscreen,
      hiddenRoles = current.hiddenRoles ++ own.flatMap(_.roles).filter { role =>
        role == UiRole.Thinking || responseOnly
      }.toSet
    )
