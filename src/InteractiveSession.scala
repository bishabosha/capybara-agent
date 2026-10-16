package capybara.agent

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import steps.result.Result

/** Terminal-independent session controller, also usable by embedding hosts and tests. */
final class InteractiveSession(
    initialPlugins: HarnessPlugins,
    reloadPlugins: () => Either[String, HarnessPlugins],
    usage: () => String,
    systemPrompt: () => String,
    execute: (String, Vector[OllamaClient.ChatMessage], ThinkingInference.ThinkingMode,
      CancellationToken, Logger) => Future[Result[Vector[OllamaClient.ChatMessage], String]],
    interruptExecution: () => Boolean = () => ReplExec.interruptActiveExecution(),
    workspace: os.Path = os.pwd
)(using ExecutionContext):
  private var plugins = initialPlugins
  private var history = Vector.empty[OllamaClient.ChatMessage]
  private var active = Option.empty[CancellationToken]
  private var thinking = ThinkingInference.ThinkingMode.Auto
  private var closed = false
  private var completedTurns = 0
  private var currentTurn = Option.empty[SessionTurn]
  private def context = PluginContext(workspace, history)

  @volatile private var completionCommands =
    HarnessPlugins.Reserved.toVector.sorted.map("/" + _) ++ initialPlugins.commandNames

  // JLine invokes completion under its display lock. Never acquire the session
  // lock there: completion callbacks and asynchronous output must not deadlock.
  def commandNames: Seq[String] = completionCommands

  private def emit(event: PluginEvent)(using logger: Logger): Unit =
    plugins.emit(event, context).foreach(error => logger.print(s"[plugin error] $error\n"))

  private def updateUiContext()(using logger: Logger): Unit =
    logger.setUiContext(TerminalUiContext(workspace.toString, completedTurns, thinking.displayName,
      if active.nonEmpty then "running" else "idle"))

  def open()(using logger: Logger): Unit = synchronized {
    logger.setUi(plugins.ui)
    updateUiContext()
    emit(PluginEvent.SessionStarted)
  }

  def close()(using Logger): Unit = synchronized {
    if !closed then
      closed = true
      val _ = cancel()
      emit(PluginEvent.SessionClosed)
      plugins.close()
  }

  private def cancel()(using Logger): Boolean =
    val previous = active
    active = None
    previous.foreach(_.cancel())
    if previous.nonEmpty then currentTurn = currentTurn.map(_.copy(state = "cancelled"))
    val interrupted = if previous.nonEmpty then interruptExecution() else false
    if previous.nonEmpty then emit(PluginEvent.TurnCancelled)
    previous.nonEmpty || interrupted

  private def ready()(using logger: Logger): Unit =
    logger.setStatus(None)
    updateUiContext()
    if logger.terminalUi.readyMessage.nonEmpty then
      logger.styled(UiRole.Success, logger.terminalUi.readyMessage + "\n")

  def accept(line: String)(using logger: Logger): Terminal.NextState = synchronized {
    if !closed then
      val trimmed = line.trim
      val parts = trimmed.split("\\s+", 2)
      parts.head match
        case "" => ()
        case "/x" =>
          logger.print(if cancel() then "[interrupt requested]\n" else "[nothing to interrupt]\n")
          ready()
        case "/usage" => logger.print(usage())
        case "/sys-prompt" => logger.print(systemPrompt() + "\n")
        case "/export" =>
          try
            val json = SessionExport.snapshot(history, systemPrompt(), workspace, thinking.displayName,
              completedTurns, currentTurn, logger.executionSnapshot).render(indent = 2)
            parts.lift(1).map(_.trim).filter(_.nonEmpty) match
              case None => logger.print(json + "\n")
              case Some(file) =>
                val path = os.Path(file, workspace)
                os.write(path, json + "\n")
                logger.print(s"[session exported to $path]\n")
          catch case NonFatal(error) => logger.print(s"[export failed] ${error.getMessage}\n")
        case "/think" =>
          parts.lift(1) match
            case None => logger.print(s"thinking mode: ${thinking.displayName}\n")
            case Some(raw) =>
              ThinkingInference.ThinkingMode.parse(raw) match
                case Some(mode) =>
                  thinking = mode
                  updateUiContext()
                  logger.print(s"thinking mode: ${mode.displayName}\n")
                case None => logger.print("usage: /think on | /think off | /think auto\n")
        case "/help" =>
          logger.print("/x interrupt · /clear reset conversation · /usage token stats · /sys-prompt system prompt\n" +
            "/think on|off|auto · /plugins list · /reload reload plugins · :q quit\n" + plugins.help + "\n")
          logger.print("/export [path] session JSON (prints without a path; saves to a new file with a path)\n")
        case "/plugins" => logger.print(plugins.describe + "\n")
        case "/clear" =>
          val _ = cancel()
          history = Vector.empty
          completedTurns = 0
          currentTurn = None
          logger.clearConversation()
          logger.print("[conversation cleared]\n")
          ready()
        case "/reload" =>
          if active.nonEmpty then logger.print("Interrupt the active turn with /x before reloading plugins.\n")
          else reloadPlugins() match
            case Left(error) => logger.print(s"[plugin error] $error\n")
            case Right(replacement) =>
              emit(PluginEvent.SessionClosed)
              plugins.close()
              plugins = replacement
              completionCommands = HarnessPlugins.Reserved.toVector.sorted.map("/" + _) ++ plugins.commandNames
              logger.setUi(plugins.ui)
              logger.clearPanels()
              updateUiContext()
              emit(PluginEvent.SessionStarted)
              logger.print("[plugins reloaded]\n")
        case command if command.startsWith("/") =>
          plugins.dispatch(trimmed, context) match
            case None => logger.print(s"Unknown command: $command. Use /help for commands.\n")
            case Some(Left(error)) => logger.print(s"[plugin error] $error\n")
            case Some(Right(action)) =>
              plugins.refreshUi() match
                case Left(error) => logger.print(s"[plugin error] $error\n")
                case Right(ui) =>
                  logger.setUi(ui)
                  action match
                    case PluginAction.Message(text) => logger.print(text + "\n")
                    case PluginAction.Submit(prompt) => submit(prompt)
                    case PluginAction.ListExecutions => logger.print(logger.executionList + "\n")
                    case PluginAction.InspectExecution(selection) =>
                      logger.print(logger.selectExecution(selection).fold(identity, identity) + "\n")
        case _ => submit(line)
    Terminal.NextState.Continue
  }

  private def submit(prompt: String)(using logger: Logger): Unit =
    plugins.transform(prompt, context) match
      case Left(error) => logger.print(s"[plugin error] $error\n")
      case Right(transformed) =>
        val _ = cancel()
        val token = CancellationToken()
        active = Some(token)
        currentTurn = Some(SessionTurn(transformed, "running"))
        updateUiContext()
        val think = ThinkingInference.shouldThink(transformed, thinking)
        logger.setStatus(Some(s"[thinking ${if think then "enabled" else "disabled"}, mode=${thinking.displayName}]"))
        emit(PluginEvent.TurnStarted(transformed))
        val running =
          try execute(transformed, history, thinking, token, logger)
          catch case NonFatal(error) => Future.failed(error)
        running.onComplete { outcome => synchronized {
          if !closed && active.contains(token) && !token.isCancelled then
            active = None
            val result = outcome match
              case Success(value) => value
              case Failure(error) => Result.Err(error.toString)
            result match
              case Result.Ok(updated) =>
                history = updated
                completedTurns += 1
                currentTurn = currentTurn.map(_.copy(state = "completed"))
                emit(PluginEvent.TurnCompleted)
              case Result.Err("cancelled") =>
                currentTurn = currentTurn.map(_.copy(state = "cancelled"))
                emit(PluginEvent.TurnCancelled)
              case Result.Err(error) =>
                currentTurn = currentTurn.map(_.copy(state = "failed", error = Some(error)))
                logger.styled(UiRole.Error, s"[error] $error\n")
                emit(PluginEvent.TurnFailed(error))
            ready()
        }}
