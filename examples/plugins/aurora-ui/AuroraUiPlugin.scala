package example

import capybara.agent.*

/** A UI plugin can contribute commands as well as appearance. */
final class AuroraUiPlugin extends HarnessPlugin:
  val id = "aurora-ui"
  val description = "A cyan terminal theme with workspace and session information"
  private var plain = false

  override val commands = Seq(
    PluginCommand("theme", "Switch appearance: /theme aurora|plain", (args, _) => args match
      case "aurora" =>
        plain = false
        PluginAction.Message("Aurora theme enabled.")
      case "plain" =>
        plain = true
        PluginAction.Message("Plain theme enabled.")
      case _ => PluginAction.Message("usage: /theme aurora | /theme plain"))
  )

  override def configureUi(current: TerminalUi): TerminalUi =
    if plain then current.copy(name = "plain", colorEnabled = false)
    else current.copy(
      name = "aurora",
      promptHeader = "capybara / {workspace}",
      prompt = "› ",
      rightPrompt = "{state} · {turns} turns · think {thinking}",
      banner = "Capybara · Aurora\n/plan · /review · /handoff · /theme plain · /help",
      readyMessage = "✓ ready",
      separator = "─" * 32,
      styles = current.styles ++ Map(
        UiRole.Prompt -> TerminalStyle(TerminalColor.BrightCyan, bold = true),
        UiRole.Assistant -> TerminalStyle(TerminalColor.Default),
        UiRole.Thinking -> TerminalStyle(TerminalColor.Gray, dim = true),
        UiRole.Tool -> TerminalStyle(TerminalColor.Magenta),
        UiRole.Status -> TerminalStyle(TerminalColor.Cyan),
        UiRole.Muted -> TerminalStyle(TerminalColor.Gray)
      )
    )
