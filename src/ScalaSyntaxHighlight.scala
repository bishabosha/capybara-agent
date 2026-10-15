package capybara.agent

import org.jline.utils.AttributedString
import scala.meta.*
import scala.util.control.NonFatal

/** Tokenize for presentation only; snippets are never parsed, compiled or evaluated here. */
object ScalaSyntaxHighlight:
  private val softKeywords = Set("as", "derives", "end", "extension", "inline", "infix", "opaque",
    "open", "transparent", "using")

  private def role(token: Token): Option[UiRole] = token match
    case _: Token.Keyword => Some(UiRole.CodeKeyword)
    case _: Token.NumericConstant[?] => Some(UiRole.CodeNumber)
    case _: Token.Comment => Some(UiRole.CodeComment)
    case _: Token.Literal => Some(UiRole.CodeString)
    case _: Token.Interpolation.Start | _: Token.Interpolation.End |
        _: Token.Interpolation.Part | _: Token.Interpolation.Id => Some(UiRole.CodeString)
    case _: Token.Ident if softKeywords(token.text) => Some(UiRole.CodeKeyword)
    case _: Token.Ident if token.text.headOption.exists(_.isUpper) => Some(UiRole.CodeType)
    case _ => None

  def highlight(source: String, ui: TerminalUi): AttributedString =
    val code = TerminalUi.stripAnsi(source).filterNot(_ == '\u007f').map { c =>
      if Character.isISOControl(c) && c != '\n' then ' ' else c
    }
    if !ui.colorEnabled then return new AttributedString(code)
    try
      given Dialect = dialects.Scala3
      code.tokenize.toOption match
        case None => new AttributedString(code)
        case Some(tokens) =>
          val out = new StringBuilder
          var end = 0
          tokens.foreach { token =>
            if token.end > token.start && token.start >= end then
              out.append(code.substring(end, token.start))
              val text = code.substring(token.start, token.end)
              out.append(role(token).map(ui.paint(_, text)).getOrElse(text))
              end = token.end
          }
          out.append(code.substring(end))
          AttributedString.fromAnsi(out.toString)
    catch case NonFatal(_) => new AttributedString(code)
