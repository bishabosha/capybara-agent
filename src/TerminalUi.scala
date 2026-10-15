package capybara.agent

/** Semantic output lets themes style streamed content without rewriting agent behavior. */
enum TerminalLayout:
  case Scrollback, Fullscreen

enum UiRole:
  case Assistant, Thinking, Tool, ToolOutput, ToolError, Success, Error, Muted, Prompt, Status
  case CodeKeyword, CodeString, CodeNumber, CodeComment, CodeType

enum TerminalColor(val code: Int):
  case Default extends TerminalColor(39)
  case Black extends TerminalColor(30)
  case Red extends TerminalColor(31)
  case Green extends TerminalColor(32)
  case Yellow extends TerminalColor(33)
  case Blue extends TerminalColor(34)
  case Magenta extends TerminalColor(35)
  case Cyan extends TerminalColor(36)
  case White extends TerminalColor(37)
  case Gray extends TerminalColor(90)
  case BrightCyan extends TerminalColor(96)

final case class TerminalStyle(
    foreground: TerminalColor = TerminalColor.Default,
    bold: Boolean = false,
    dim: Boolean = false
):
  def ansi: String =
    val codes = Vector(foreground.code) ++ Option.when(bold)(1) ++ Option.when(dim)(2)
    s"\u001b[${codes.mkString(";")}m"

enum UiPanelContent:
  case Streams, ExecutionCode, ExecutionOutput

final case class UiPanel(id: String, title: String, roles: Set[UiRole], weight: Int = 1,
    content: UiPanelContent = UiPanelContent.Streams)

final case class TerminalUiContext(
    workspace: String = os.pwd.toString,
    turns: Int = 0,
    thinking: String = "auto",
    state: String = "idle"
):
  def expand(template: String): String =
    val values = Map("cwd" -> workspace, "workspace" -> os.Path(workspace).last,
      "turns" -> turns.toString, "thinking" -> thinking, "state" -> state)
    "\\{(cwd|workspace|turns|thinking|state)\\}".r.replaceAllIn(template,
      matched => java.util.regex.Matcher.quoteReplacement(values(matched.group(1))))

/** Immutable view configuration, composed by Scala plugins in load order. */
final case class TerminalUi(
    name: String = "default",
    prompt: String = "> ",
    promptHeader: String = "",
    rightPrompt: String = "",
    banner: String = "Type /help for commands, /x to interrupt, :q to quit. Tab completes commands.",
    goodbye: String = "Goodbye!",
    readyMessage: String = "[your turn]",
    separator: String = "-----",
    showStatus: Boolean = true,
    colorEnabled: Boolean = true,
    styles: Map[UiRole, TerminalStyle] = TerminalUi.DefaultStyles,
    panels: Vector[UiPanel] = Vector.empty,
    panelRows: Int = 6,
    layout: TerminalLayout = TerminalLayout.Scrollback,
    hiddenRoles: Set[UiRole] = Set.empty
):
  def validate(): Unit =
    require(name.nonEmpty && prompt.nonEmpty, "UI name and prompt must not be empty")
    require(!rightPrompt.contains('\n') && !rightPrompt.contains('\r'), "Right prompt must be one line")
    require(panelRows >= 3 && panelRows <= 20, "Panel rows must be between 3 and 20")
    require(panels.size <= 4, "At most four panels are supported")
    require(panels.map(_.id).distinct.size == panels.size, "Duplicate panel id")
    require(hiddenRoles.subsetOf(panels.flatMap(_.roles).toSet), "Hidden output roles must have a panel")
    require(panels.forall(p => p.id.matches("[a-z][a-z0-9-]*") && p.roles.nonEmpty && p.weight >= 1 && p.weight <= 10),
      "Panels need a valid id, output roles, and a weight between 1 and 10")

  def paint(role: UiRole, text: String): String =
    if !colorEnabled then TerminalUi.stripAnsi(text)
    else styles.get(role) match
      case Some(style) => style.ansi + text + Console.RESET
      case None => text

  def renderPrompt(context: TerminalUiContext): String =
    val header = if promptHeader.isEmpty then "" else paint(UiRole.Muted, context.expand(promptHeader)) + "\n"
    header + paint(UiRole.Prompt, context.expand(prompt))

  def renderRightPrompt(context: TerminalUiContext): String = paint(UiRole.Muted, context.expand(rightPrompt))

object TerminalUi:
  val DefaultStyles: Map[UiRole, TerminalStyle] = Map(
    UiRole.Assistant -> TerminalStyle(bold = true),
    UiRole.Thinking -> TerminalStyle(TerminalColor.Cyan),
    UiRole.Tool -> TerminalStyle(TerminalColor.Yellow),
    UiRole.ToolOutput -> TerminalStyle(TerminalColor.Green),
    UiRole.ToolError -> TerminalStyle(TerminalColor.Red),
    UiRole.Success -> TerminalStyle(TerminalColor.Green),
    UiRole.Error -> TerminalStyle(TerminalColor.Red),
    UiRole.Muted -> TerminalStyle(dim = true),
    UiRole.Status -> TerminalStyle(TerminalColor.Cyan),
    UiRole.CodeKeyword -> TerminalStyle(TerminalColor.Magenta, bold = true),
    UiRole.CodeString -> TerminalStyle(TerminalColor.Green),
    UiRole.CodeNumber -> TerminalStyle(TerminalColor.BrightCyan),
    UiRole.CodeComment -> TerminalStyle(TerminalColor.Gray, dim = true),
    UiRole.CodeType -> TerminalStyle(TerminalColor.Yellow)
  )
  def stripAnsi(text: String): String = text.replaceAll("\u001b\\[[0-9;]*m", "")
