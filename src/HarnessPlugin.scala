package capybara.agent

/** Trusted host extensions. These are separate from capability-checked model skills. */
trait HarnessPlugin extends AutoCloseable:
  def id: String
  def description: String
  def commands: Seq[PluginCommand] = Nil
  def transformPrompt(prompt: String, context: PluginContext): String = prompt
  def onEvent(event: PluginEvent, context: PluginContext): Unit = ()
  def close(): Unit = ()

final case class PluginContext(workspace: os.Path, history: Vector[OllamaClient.ChatMessage])

final case class PluginCommand(
    name: String,
    description: String,
    run: (String, PluginContext) => PluginAction
)

enum PluginAction:
  case Message(text: String)
  case Submit(prompt: String)

enum PluginEvent:
  case SessionStarted, SessionClosed
  case TurnStarted(prompt: String)
  case TurnCompleted
  case TurnFailed(error: String)
  case TurnCancelled
