package capybara.agent

import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import scala.util.Using

import scala.concurrent.Future

object Terminal {
  def run(prompt: Logger ?=> String => Future[Any]): Unit = {
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

    def loop()(using logger: Logger): Unit =
      logger.flush()
      try
        reader.readLine("> ") match
          case null =>
            ()
          case line if line.trim.equalsIgnoreCase(":q") =>
            ()
          case line =>
            awaitAll(
              prompt(line)
            )
            loop()
      catch
        case _: UserInterruptException =>
          terminal.writer.println("^C")
          loop()
        case _: EndOfFileException =>
          ()

    terminal.writer.println("Type something. Type 'exit' to quit.")
    Using.resource(Logger.TerminalLogger(terminal.writer)) { logger =>
      loop()(using logger)
    }
    terminal.writer.println("Goodbye!")
    terminal.close()
  }
}
