package capybara.agent

import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import scala.util.Using
import scala.annotation.tailrec

object Terminal {
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
          reader.readLine("> ") match
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

    terminal.writer.println("Type something. Type 'exit' to quit.")
    Using.resource(Logger.TerminalLogger(terminal.writer)) { logger =>
      loop()(using logger)
    }
    terminal.writer.println("Goodbye!")
    terminal.close()
  }
}
