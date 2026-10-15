package capybara.agent

import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.StandardOpenOption.{CREATE, WRITE}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.NonFatal

/** Only the explicitly selected Apple provider calls this installer. No work runs at class loading. */
private[agent] object AppleFmEnvironment:
  val SdkVersion = "0.2.1"

  def python(token: CancellationToken, progress: String => Unit): String =
    val cache = os.Path(sys.props("user.home")) / "Library" / "Caches" / "capybara-agent" / "apple-fm"
    ensure(cache, token, progress, runCommand)

  private[agent] def ensure(cache: os.Path, token: CancellationToken, progress: String => Unit,
      run: (Seq[String], CancellationToken) => Unit): String =
    def checkCancelled(): Unit = if token.isCancelled then throw IllegalStateException("cancelled")
    checkCancelled()
    os.makeDir.all(cache)
    val channel = FileChannel.open((cache / "setup.lock").toNIO, CREATE, WRITE)
    var lock: java.nio.channels.FileLock = null
    try
      while lock == null do
        checkCancelled()
        try lock = channel.tryLock()
        catch case _: OverlappingFileLockException => ()
        if lock == null then Thread.sleep(50)
      val environment = cache / s"sdk-$SdkVersion"
      val python = environment / "bin" / "python"
      val ready = environment / ".capybara-ready"
      if !os.exists(ready) || !java.nio.file.Files.isExecutable(python.toNIO) ||
          os.read(ready).trim != SdkVersion then
        progress("Setting up Apple FM Python environment (first use)…")
        // Only this versioned, host-owned directory is replaced after interrupted setup.
        os.remove.all(environment)
        run(Seq("python3", "-m", "venv", environment.toString), token)
        checkCancelled()
        progress("Installing Apple Foundation Models SDK…")
        run(Seq(python.toString, "-m", "pip", "install", "--disable-pip-version-check", "--no-input",
          s"apple-fm-sdk==$SdkVersion"), token)
        checkCancelled()
        run(Seq(python.toString, "-c",
          s"import apple_fm_sdk; import importlib.metadata; assert importlib.metadata.version('apple-fm-sdk') == '$SdkVersion'"), token)
        checkCancelled()
        os.write.over(ready, SdkVersion + "\n")
      python.toString
    finally
      if lock != null then lock.release()
      channel.close()

  private def runCommand(command: Seq[String], token: CancellationToken): Unit =
    val process = try new ProcessBuilder(command*).redirectErrorStream(true).start()
      catch case NonFatal(error) =>
        throw IllegalStateException("Apple FM setup needs Python 3.10+ with venv/pip support. " + error.getMessage, error)
    def terminate(): Unit =
      val descendants = process.toHandle.descendants()
      try descendants.forEach(child => { val _ = child.destroyForcibly() })
      finally descendants.close()
      val _ = process.destroyForcibly()
    val cancellation = token.onCancel(terminate())
    val output = new StringBuilder
    val reader = new java.io.InputStreamReader(process.getInputStream, UTF_8)
    try
      val buffer = new Array[Char](4096)
      var count = reader.read(buffer)
      while count >= 0 do
        output.appendAll(buffer, 0, count)
        if output.length > 16384 then output.delete(0, output.length - 16384)
        count = reader.read(buffer)
      val exit = process.waitFor()
      if token.isCancelled then throw IllegalStateException("cancelled")
      if exit != 0 then throw IllegalStateException(
        "Apple FM environment setup failed; the next request will retry. " + output.toString)
    finally
      cancellation.close()
      terminate()
      reader.close()
      val _ = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
