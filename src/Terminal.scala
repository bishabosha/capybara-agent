package capybara.agent

import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import scala.util.Using
import scala.annotation.tailrec

object Terminal {
  private val Prompt = "> "

  enum NextState:
    case Continue
    case Exit
  def run(prompt: Logger ?=> String => NextState): Unit = {
    val terminal =
      TerminalBuilder
        .builder()
        .system(true)
        .build()

    val reader =
      LineReaderBuilder
        .builder()
        .terminal(terminal)
        .build()

    @tailrec
    def loop()(using logger: Logger): Unit =
      logger.flush()
      val nextState =
        try
          reader.readLine(logger.prompt(Prompt)) match
            case null =>
              NextState.Exit
            case line if line.trim.equalsIgnoreCase(":q") =>
              NextState.Exit
            case line =>
              prompt(line)
        catch
          case _: UserInterruptException =>
            terminal.writer.println("^C")
            NextState.Continue
          case _: EndOfFileException =>
            NextState.Exit
      if nextState == NextState.Continue then loop()

    terminal.writer.println(
      "Type something. Type a new line while the agent is responding to interrupt. Type ':q' to quit. Type '/usage' for token stats. Type '/sys-prompt' to print the system prompt. Type '/think on|off|auto' to set thinking mode."
    )
    Using.resource(Logger.TerminalLogger(reader, terminal.writer, Prompt)) { logger =>
      loop()(using logger)
    }
    terminal.writer.println("Goodbye!")
    terminal.close()
  }
}
