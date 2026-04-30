package capybara.agent

import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import scala.util.Using
import scala.annotation.tailrec

object Terminal {
  case object Continue
  def run(prompt: Logger ?=> String => Continue.type): Unit = {
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
      try
        reader.readLine("> ") match
          case null =>
            ()
          case line if line.trim.equalsIgnoreCase(":q") =>
            ()
          case line =>
            val _ = prompt(line)
      catch
        case _: UserInterruptException =>
          terminal.writer.println("^C")
        case _: EndOfFileException =>
          ()
      loop()

    terminal.writer.println("Type something. Type 'exit' to quit.")
    Using.resource(Logger.TerminalLogger(terminal.writer)) { logger =>
      loop()(using logger)
    }
    terminal.writer.println("Goodbye!")
    terminal.close()
  }
}
