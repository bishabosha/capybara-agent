package capybara.agent

import OllamaClient.*
import steps.result.Result
import upickle.default.*
import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, BufferedWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.util.control.NonFatal

/** Native Apple support is an optional subprocess transport, loaded only on selection. */
object AppleFmClient:
  val Provider = "apple-fm"

  def request[T: Writer](messages: Seq[ChatMessage], tools: Vector[ToolDefinition],
      toolParsers: Map[String, ReadWriter[T]], cancellationToken: CancellationToken,
      python: String, model: String,
      onStatus: String => Unit = _ => (), onNotice: String => Unit = _ => ()): ChunkRequest[Chunk[T]] =
    requestWithCommand(messages, tools, toolParsers, cancellationToken, () => {
      require(sys.props.getOrElse("os.name", "").startsWith("Mac"),
        "apple-fm requires macOS with Apple Intelligence; select another provider on this platform")
      require(model == "system", "apple-fm currently supports CAPYBARA_MODEL=system only")
      val executable = if python.nonEmpty then python else AppleFmEnvironment.python(cancellationToken, onStatus)
      onStatus("Waiting for Apple Foundation Models…")
      Seq(executable, "-u", bridgePath.toString)
    }, onStatus, onNotice)

  // This resource is never read or extracted for another provider.
  private lazy val bridgePath =
    val source = Option(getClass.getResourceAsStream("/apple_fm_bridge.py")).getOrElse(
      throw IllegalStateException("Bundled apple_fm_bridge.py is missing"))
    val path = Files.createTempFile("capybara-apple-fm-", ".py")
    try Files.copy(source, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    finally source.close()
    path.toFile.deleteOnExit()
    path

  private[agent] def payload(messages: Seq[ChatMessage], tools: Vector[ToolDefinition]): ujson.Value =
    require(tools.size == 1 && tools.head.function.name == "run_scala_code",
      "apple-fm supports the run_scala_code tool")
    val saved = messages.lastIndexWhere(_.providerState.contains(Provider))
    // Tool messages following the state-bearing assistant are already in the native snapshot.
    val pending = messages.drop(saved + 1).filter(message =>
      !Set("system", "tool").contains(message.json("role").str))
    require(pending.size == 1 && pending.head.json("role").str == "user",
      "apple-fm needs a fresh chat or its native transcript; use /clear when changing providers")
    val instructions = messages.filter(_.json("role").str == "system").map(_.json("content").str).mkString("\n\n")
    ujson.Obj(
      "instructions" -> instructions,
      "prompt" -> pending.head.json("content"),
      "tool_description" -> tools.head.function.description,
      "transcript" -> (if saved >= 0 then messages(saved).providerState(Provider) else ujson.Null))

  private[agent] def requestWithCommand[T: Writer](messages: Seq[ChatMessage], tools: Vector[ToolDefinition],
      toolParsers: Map[String, ReadWriter[T]], cancellationToken: CancellationToken,
      command: () => Seq[String], onStatus: String => Unit = _ => (),
      onNotice: String => Unit = _ => ()): ChunkRequest[Chunk[T]] =
    val output = new ChunkOutput[Chunk[T]]()
    ChunkRequest.local(output, cancellationToken, completesToolLoop = true) {
      val input = payload(messages, tools)
      val process = new ProcessBuilder(command()*).start()
      val writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream, UTF_8))
      val reader = new BufferedReader(new InputStreamReader(process.getInputStream, UTF_8))
      val errors = new StringBuilder
      // Never inherit Python/SDK diagnostics into the full-screen terminal.
      val errorReader = new Thread(() => {
        val stream = new InputStreamReader(process.getErrorStream, UTF_8)
        val buffer = new Array[Char](1024)
        try
          var count = stream.read(buffer)
          while count >= 0 do
            errors.synchronized {
              errors.appendAll(buffer, 0, count)
              if errors.length > 8192 then errors.delete(0, errors.length - 8192)
            }
            count = stream.read(buffer)
        catch case NonFatal(_) => ()
        finally stream.close()
      }, "apple-fm-stderr")
      errorReader.setDaemon(true)
      errorReader.start()
      def send(value: ujson.Value): Unit = writer.synchronized {
        writer.write(ujson.write(value))
        writer.newLine()
        writer.flush()
      }
      val cancel = cancellationToken.onCancel(process.destroyForcibly())
      try
        send(input)
        val decoder = new Decoder(toolParsers, output, (id, result) => {
          try send(ujson.Obj("type" -> "tool_result", "id" -> id, "result" -> result))
          catch case NonFatal(_) => process.destroyForcibly()
        }, onStatus, onNotice)
        var line = reader.readLine()
        while line != null && output.isRunning && !cancellationToken.isCancelled do
          require(line.length <= 4 * 1024 * 1024, "Apple provider event is too large")
          decoder.accept(ujson.read(line))
          if output.isRunning && !cancellationToken.isCancelled then line = reader.readLine()
        if cancellationToken.isCancelled then
          output.close()
          Result.Err("cancelled")
        else if output.isRunning then
          errorReader.join(250)
          val diagnostic = errors.synchronized { errors.toString }
          throw IllegalStateException("Apple SDK bridge ended before completing the turn. " + diagnostic)
        else Result.Ok(())
      finally
        cancel.close()
        // Close stdin first to release the SDK bridge's input reader.
        try writer.close() catch case NonFatal(_) => ()
        process.destroyForcibly()
        reader.close()
        val _ = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    }

  private[agent] final class Decoder[T](parsers: Map[String, ReadWriter[T]],
      output: ChunkOutput[Chunk[T]], reply: (String, String) => Unit,
      onStatus: String => Unit = _ => (), onNotice: String => Unit = _ => ()):

    private var ids = Set.empty[String]
    private var pending = Set.empty[String]
    private var savedState = Option.empty[ujson.Value]

    def accept(event: ujson.Value): Unit =
      event("type").str match
        case "status" => onStatus(event("text").str)
        case "notice" => onNotice(event("text").str)
        case "content" => output.push(Chunk.Content(event("text").str))
        case "tool_call" =>
          val id = event("id").str
          val name = event("name").str
          require(id.nonEmpty && !ids(id) && ids.size < 32, "Invalid, duplicate, or excessive Apple tool call")
          require(name == "run_scala_code", "Unknown Apple tool name")
          val arguments = parseArguments(name, event("arguments"), parsers) match
            case Result.Ok(value) => value
            case Result.Err(error) => throw IllegalArgumentException(s"Invalid Apple tool arguments: $error")
          ids += id
          this.synchronized { pending += id }
          val sink: String => Unit = result => this.synchronized {
            if pending(id) then
              reply(id, result)
              pending -= id
          }
          output.push(Chunk.Tools(Vector(ToolCall(name, arguments, Some(id), Some(sink)))))
        case "state" => savedState = Some(event("transcript"))
        case "done" =>
          require(this.synchronized { pending.isEmpty }, "Apple turn finished with unresolved tools")
          require(savedState.nonEmpty, "Apple turn finished without its native transcript")
          output.push(Chunk.ProviderState(Provider, savedState.get))
          output.close()
        case "error" => throw IllegalStateException("Apple SDK: " + event("message").str)
        case kind => throw IllegalArgumentException(s"Unknown Apple provider event: $kind")
