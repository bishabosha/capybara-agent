package capybara.agent

/** Useful prompt workflows, available without installing an extension. */
final class WorkflowPlugin extends HarnessPlugin:
  val id = "workflows"
  val description = "Planning, code review, and session handoffs"

  override val commands: Seq[PluginCommand] = Seq(
    PluginCommand("plan", "Explore a goal and propose an implementation plan", (args, _) =>
      if args.isBlank then PluginAction.Message("usage: /plan <goal>")
      else PluginAction.Submit(s"""Plan the following task. Inspect relevant code using available skills.
        |Do not edit files or perform external writes. Explain the approach, tradeoffs,
        |implementation steps, and useful validation. Ask about material ambiguities.
        |
        |Task: $args""".stripMargin)),
    PluginCommand("review", "Review code for concrete bugs and missing validation", (args, _) =>
      PluginAction.Submit(s"""Review ${if args.isBlank then "the current workspace changes" else args}.
        |Use available skills to inspect the code. Do not edit files or perform external writes.
        |Prioritize concrete regressions, correctness issues, and missing validation.
        |Give file locations and explain the triggering scenario. If no issues are found,
        |say so and state what could not be verified.""".stripMargin)),
    PluginCommand("handoff", "Summarize this conversation for the next session", (args, context) =>
      if context.history.isEmpty then PluginAction.Message("No conversation to hand off yet.")
      else PluginAction.Submit(s"""Write a concise handoff from this conversation: objective,
        |decisions, changes made, validation results, unresolved issues, and concrete next steps.
        |Separate verified facts from assumptions. Do not take further actions.
        |${if args.isBlank then "" else s"Additional focus: $args"}""".stripMargin))
  )
