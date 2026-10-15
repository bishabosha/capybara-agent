package capybara.agent

import org.jline.reader.{EndOfFileException, UserInterruptException}
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import scala.util.Using
import scala.annotation.tailrec
import org.jline.reader.impl.completer.StringsCompleter
import scala.jdk.CollectionConverters.*

object Terminal {
  private val Prompt = "> "

  enum NextState:
    case Continue
    case Exit
  def run(
      prompt: Logger ?=> String => NextState,
      commands: () => Seq[String] = () => Nil,
      onStart: Logger ?=> Unit = (),
      onClose: Logger ?=> Unit = ()
  ): Unit = {
    val terminal =
      TerminalBuilder
        .builder()
        .system(true)
        .build()

    val reader =
      LineReaderBuilder
        .builder()
        .terminal(terminal)
        .completer(new StringsCompleter(new java.util.function.Supplier[java.util.Collection[String]] {
          def get(): java.util.Collection[String] = commands().asJava
        }))
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

    try
      terminal.writer.println("Type /help for commands, /x to interrupt, :q to quit. Tab completes commands.")
      Using.resource(Logger.TerminalLogger(reader, terminal.writer, Prompt)) { logger =>
        try
          onStart(using logger)
          loop()(using logger)
        finally onClose(using logger)
      }
      terminal.writer.println("Goodbye!")
    finally terminal.close()
  }
}
