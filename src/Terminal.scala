package capybara.agent

import org.jline.reader.{EndOfFileException, UserInterruptException}
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

    val reader = new TerminalLineReader(terminal)
    reader.setCompleter(new StringsCompleter(new java.util.function.Supplier[java.util.Collection[String]] {
      def get(): java.util.Collection[String] = commands().asJava
    }))

    @tailrec
    def loop()(using logger: Logger): Unit =
      logger.flush()
      val nextState =
        try
          reader.readLine(logger.prompt(Prompt), logger.rightPrompt, null: Character, null: String) match
            case null =>
              NextState.Exit
            case line if line.trim.equalsIgnoreCase(":q") =>
              NextState.Exit
            case line =>
              logger.echoInput(line)
              prompt(line)
        catch
          case _: UserInterruptException =>
            terminal.writer.println("^C")
            NextState.Continue
          case _: EndOfFileException =>
            NextState.Exit
      if nextState == NextState.Continue then loop()

    try
      Using.resource(Logger.TerminalLogger(reader, terminal.writer, Prompt)) { logger =>
        try
          onStart(using logger)
          if logger.terminalUi.banner.nonEmpty then logger.print(logger.terminalUi.banner + "\n")
          loop()(using logger)
          if logger.terminalUi.goodbye.nonEmpty then logger.print(logger.terminalUi.goodbye + "\n")
        finally onClose(using logger)
      }
    finally terminal.close()
  }
}
