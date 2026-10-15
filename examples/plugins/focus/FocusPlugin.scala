package example

import capybara.agent.*

/** State belongs to this plugin instance and is reset by /reload. */
final class FocusPlugin extends HarnessPlugin:
  val id = "focus"
  val description = "Keep a session focused and count completed turns"
  private var focus = Option.empty[String]
  private var completed = 0

  override val commands = Seq(
    PluginCommand("focus", "Set a session instruction; /focus off clears it", (args, _) =>
      if args.isBlank then PluginAction.Message(focus.fold("No focus set.")(f => s"Focus: $f"))
      else
        focus = Option.when(args != "off")(args)
        PluginAction.Message(focus.fold("Focus cleared.")(f => s"Focus: $f"))),
    PluginCommand("flow-stats", "Show completed turns since this plugin was loaded", (_, _) =>
      PluginAction.Message(s"Completed turns: $completed"))
  )

  override def transformPrompt(prompt: String, context: PluginContext): String =
    focus.fold(prompt)(instruction => s"Session instruction: $instruction\n\n$prompt")

  override def onEvent(event: PluginEvent, context: PluginContext): Unit = event match
    case PluginEvent.TurnCompleted => completed += 1
    case _ => ()
