//> using file Interface.scala
package workshop.internal

import scala.language.experimental.captureChecking
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.util.control.NonFatal

/** JDK-only adapter: skills load in an isolated classloader, without host dependencies. */
private class CodingImpl(root: String, node: String, python: String, hostPath: String)
    extends workshop.Coding {
  private class PolicyError(code: String) extends IllegalStateException(code)
  private val errorCodes = Set(
    "WORKSHOP_NOT_CONFIGURED", "WORKSPACE_CHANGED", "PATH_DENIED", "WRITE_DENIED",
    "CONTENT_DENIED", "CONTENT_CHANGED", "SANDBOX_UNAVAILABLE", "INVALID_REQUEST",
    "WORKSHOP_BACKEND_FAILED"
  )

  private def invoke(operation: String, arguments: String*): String =
    var child: Process | Null = null
    try
      if arguments.exists(_.getBytes(UTF_8).length > 16384) then throw PolicyError("CONTENT_DENIED")
      val helper = Path.of("").toAbsolutePath.resolve("scripts/workshop.py").toString
      val builder = new ProcessBuilder(python, "-I", "-S", helper,
        "rpc-lines", "--repo", root, "--node", node)
      builder.environment().clear()
      val _ = builder.environment().put("PATH", hostPath)
      val _ = builder.redirectError(ProcessBuilder.Redirect.DISCARD)
      val process = builder.start()
      child = process
      val reader = CompletableFuture.supplyAsync(() => process.getInputStream.readNBytes(65537))
      val fields = operation +: arguments.toVector
      val input = fields.map(s => Base64.getEncoder.encodeToString(s.getBytes(UTF_8))).mkString("", "\n", "\n")
      val out = process.getOutputStream
      try out.write(input.getBytes(UTF_8))
      finally out.close()
      if !process.waitFor(25, TimeUnit.SECONDS) then throw PolicyError("WORKSHOP_BACKEND_FAILED")
      val bytes = reader.get(1, TimeUnit.SECONDS)
      if process.exitValue() != 0 || bytes.length > 65536 then throw PolicyError("WORKSHOP_BACKEND_FAILED")
      val response = new String(bytes, UTF_8).split("\n", -1)
      if response.length != 3 || response(2).nonEmpty then throw PolicyError("WORKSHOP_BACKEND_FAILED")
      if response(0) == "ERROR" then
        throw PolicyError(if errorCodes(response(1)) then response(1) else "WORKSHOP_BACKEND_FAILED")
      if response(0) != "OK" then throw PolicyError("WORKSHOP_BACKEND_FAILED")
      new String(Base64.getDecoder.decode(response(1)), UTF_8)
    catch
      case error: PolicyError => throw error
      case NonFatal(_) => throw PolicyError("WORKSHOP_BACKEND_FAILED")
    finally
      if child != null then
        val descendants = child.descendants()
        try descendants.forEach(handle => { val _ = handle.destroyForcibly() })
        finally descendants.close()
        val _ = child.destroyForcibly()

  def read(path: String): String = invoke("read", path)

  def replace(path: String, expected: String, next: String): Unit =
    val _ = invoke("replace", path, expected, next)

  def transformSource(f: String -> String): Unit =
    val before = read("src/word-count.mjs")
    replace("src/word-count.mjs", before, f(before))

  def test(): workshop.TestSummary =
    val status = invoke("test")
    if !Set("passed", "failed", "timeout")(status) then throw PolicyError("WORKSHOP_BACKEND_FAILED")
    workshop.TestSummary(status)

  def diff(): String = invoke("diff")

  def sandboxChecks(): Seq[String] = invoke("checks").split(",").toVector
}

@caps.rejectSafe
def impl(): workshop.Coding =
  val root = sys.env.getOrElse("CAPYBARA_WORKSHOP_ROOT",
    throw IllegalStateException("WORKSHOP_NOT_CONFIGURED"))
  configured(root, sys.env.getOrElse("CAPYBARA_WORKSHOP_NODE", "node"),
    sys.env.getOrElse("CAPYBARA_WORKSHOP_PYTHON", "/usr/bin/python3"))

/** Explicit trusted wiring for integration tests and embedding. */
@caps.rejectSafe
def configured(root: String, node: String, python: String): workshop.Coding =
  new CodingImpl(root, node, python, sys.env.getOrElse("PATH", "/usr/bin:/bin"))
