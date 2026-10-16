//> using file Interface.scala
package capybara.skills.inspectscalaapi.internal

import scala.language.experimental.captureChecking
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

private class ScalaApiInspectorImpl(command: Seq[String], workspace: Path, workingDirectory: Path) extends capybara.skills.inspectscalaapi.ScalaApiInspector {
  private def inspect(operation: String, skill: String, query: String): String = {
    require(skill.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*"), "Invalid skill name")
    require(query.nonEmpty && query.length <= 256 && !query.startsWith("-") &&
      !query.exists(_.isControl), "Invalid symbol or query")
    val installed = Seq("skills", "builtin-skills").exists(directory =>
      Files.isRegularFile(workspace.resolve(directory).resolve(skill).resolve("SKILL.md")))
    require(installed, "Unknown skill")
    require(Files.isRegularFile(workspace.resolve(".agent-runtime/interface").resolve(skill).resolve("sigs.jar")),
      "Skill API not prepared; select both inspect-scala-api and the target skill in universes")
    val coordinate = s"dev.capybara.skills:${skill}-api_3:0.1.0-SNAPSHOT"
    val limit = if operation == "get" then Seq.empty else Seq("--limit", "20")
    val arguments = command ++ Seq(operation + "-external") ++
      limit ++ Seq(coordinate, query)
    // A file avoids pipe backpressure while waiting with a bounded timeout.
    val output = Files.createTempFile("capybara-cellar-", ".txt")
    try {
      val process = new ProcessBuilder(arguments*)
        .directory(workingDirectory.toFile).redirectErrorStream(true).redirectOutput(output.toFile).start()
      try {
        require(process.waitFor(30, TimeUnit.SECONDS), "Cellar timed out")
        val stream = Files.newInputStream(output)
        val bytes = try stream.readNBytes(65537) finally stream.close()
        val text = new String(bytes.take(65536), UTF_8)
        require(process.exitValue() == 0, "Cellar failed: " + text)
        if bytes.length > 65536 then text + "\n[Truncated; request a more specific symbol]" else text
      } finally {
        if process.isAlive then { val _ = process.destroyForcibly() }
      }
    } finally { val _ = Files.deleteIfExists(output) }
  }

  def get(skill: String, symbol: String): String = inspect("get", skill, symbol)
  def list(skill: String, packageOrType: String): String = inspect("list", skill, packageOrType)
  def search(skill: String, query: String): String = inspect("search", skill, query)
}

@caps.rejectSafe
def impl(): capybara.skills.inspectscalaapi.ScalaApiInspector = {
  val workspace = Path.of("").toAbsolutePath
  sys.env.get("CAPYBARA_CELLAR_BIN") match {
    case Some(executable) => new ScalaApiInspectorImpl(Seq(executable), workspace, workspace)
    case None =>
      val runtime = workspace.resolve(".agent-runtime/cellar")
      val classpath = Files.readString(runtime.resolve("classpath.txt"), UTF_8).trim
      val java = Path.of(sys.props("java.home"), "bin", "java").toString
      new ScalaApiInspectorImpl(Seq(java, "-cp", classpath, "cellar.cli.CellarApp"), workspace, runtime)
  }
}

/** Trusted test/embedding configuration; never exposed through the public API JAR. */
@caps.rejectSafe
def configured(executable: String, workspace: String): capybara.skills.inspectscalaapi.ScalaApiInspector =
  new ScalaApiInspectorImpl(Seq(executable), Path.of(workspace).toAbsolutePath, Path.of(workspace).toAbsolutePath)
