//> using file Interface.scala
package githubskill.internal

import scala.language.experimental.captureChecking
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.util.control.NonFatal

/** JDK-only bridge. The isolated skill REPL does not inherit host libraries. */
private class GitHubImpl(profile: String, python: String, helper: String, token: String)
    extends githubskill.GitHub {
  private class PolicyError(code: String) extends IllegalStateException(code)
  private val codes = Set(
    "NOT_CONFIGURED", "INVALID_REQUEST", "POLICY_DENIED", "PATH_DENIED", "WRITE_DENIED",
    "CONTENT_DENIED", "CONTENT_CHANGED", "SESSION_EXISTS", "SESSION_REQUIRED", "BASE_CHANGED",
    "BRANCH_CHANGED", "NO_CHANGES", "TESTS_REQUIRED", "TESTS_NOT_CONFIGURED", "SANDBOX_UNAVAILABLE",
    "PUSH_REQUIRED", "AUTH_FAILED", "NOT_FOUND", "RATE_LIMITED", "GITHUB_UNAVAILABLE",
    "INVALID_RESPONSE", "GITHUB_REQUEST_REJECTED", "BACKEND_FAILED"
  )

  private def invoke(operation: String, arguments: String*): Vector[String] =
    var child: Process | Null = null
    try
      if arguments.exists(_.getBytes(UTF_8).length > 65536) then throw PolicyError("CONTENT_DENIED")
      val builder = new ProcessBuilder(python, "-I", "-S", helper, "rpc-lines", "--profile", profile)
      builder.environment().clear()
      val _ = builder.environment().put("PATH", "/usr/bin:/bin")
      val _ = builder.environment().put("CAPYBARA_GITHUB_TOKEN", token)
      val _ = builder.redirectError(ProcessBuilder.Redirect.DISCARD)
      val process = builder.start()
      child = process
      val reader = CompletableFuture.supplyAsync(() => process.getInputStream.readNBytes(1048577))
      val fields = operation +: arguments.toVector
      val input = fields.map(s => Base64.getEncoder.encodeToString(s.getBytes(UTF_8))).mkString("", "\n", "\n")
      val out = process.getOutputStream
      try out.write(input.getBytes(UTF_8))
      finally out.close()
      if !process.waitFor(90, TimeUnit.SECONDS) then throw PolicyError("BACKEND_FAILED")
      val bytes = reader.get(1, TimeUnit.SECONDS)
      if process.exitValue() != 0 || bytes.length > 1048576 then throw PolicyError("BACKEND_FAILED")
      val response = new String(bytes, UTF_8).split("\n", -1).toVector
      if response.lastOption != Some("") then throw PolicyError("BACKEND_FAILED")
      val lines = response.dropRight(1)
      if lines.headOption == Some("ERROR") then
        throw PolicyError(lines.lift(1).filter(codes).getOrElse("BACKEND_FAILED"))
      if lines.headOption != Some("OK") then throw PolicyError("BACKEND_FAILED")
      lines.tail.map(s => new String(Base64.getDecoder.decode(s), UTF_8))
    catch
      case error: PolicyError => throw error
      case NonFatal(_) => throw PolicyError("BACKEND_FAILED")
    finally
      if child != null then
        val descendants = child.descendants()
        try descendants.forEach(handle => { val _ = handle.destroyForcibly() })
        finally descendants.close()
        val _ = child.destroyForcibly()

  private def fields(operation: String, count: Int, arguments: String*): Vector[String] =
    val values = invoke(operation, arguments*)
    if values.size != count then throw PolicyError("INVALID_RESPONSE")
    values

  def listIssues(page: Int): Seq[githubskill.IssueSummary] =
    val values = invoke("issues", page.toString)
    if values.size % 3 != 0 then throw PolicyError("INVALID_RESPONSE")
    values.grouped(3).map(r => githubskill.IssueSummary(r(0).toInt, r(1), r(2))).toVector

  def lookupIssue(number: Int): githubskill.Issue =
    val r = fields("issue", 4, number.toString)
    githubskill.Issue(r(0).toInt, r(1), r(2), r(3))

  def start(number: Int): githubskill.Workspace =
    val r = fields("start", 3, number.toString)
    githubskill.Workspace(r(0).toInt, r(1), r(2))

  def files(): Seq[String] = invoke("files")
  def read(path: String): String = fields("read", 1, path).head

  def replace(path: String, expected: String, next: String): Unit =
    val _ = fields("replace", 0, path, expected, next)

  def transform(path: String)(f: String -> String): Unit =
    val before = read(path)
    replace(path, before, f(before))

  def diff(): String = fields("diff", 1).head

  def test(): githubskill.TestResult =
    val status = fields("test", 1).head
    if !Set("passed", "failed", "timeout")(status) then throw PolicyError("INVALID_RESPONSE")
    githubskill.TestResult(status)

  def push(message: String): githubskill.Publication =
    val r = fields("push", 2, message)
    githubskill.Publication(r(0), r(1))

  def createPullRequest(title: String, body: String): githubskill.PullRequest =
    val r = fields("pr", 2, title, body)
    githubskill.PullRequest(r(0).toInt, r(1))

  def checks(): githubskill.CheckSummary =
    val r = fields("checks", 2)
    if !Set("passed", "failed", "pending")(r(1)) then throw PolicyError("INVALID_RESPONSE")
    githubskill.CheckSummary(r(0), r(1))
}

@caps.rejectSafe
def impl(): githubskill.GitHub =
  val profile = sys.env.getOrElse("CAPYBARA_GITHUB_PROFILE", throw IllegalStateException("NOT_CONFIGURED"))
  val token = sys.env.getOrElse("CAPYBARA_GITHUB_TOKEN", throw IllegalStateException("AUTH_FAILED"))
  configured(profile, sys.env.getOrElse("CAPYBARA_GITHUB_PYTHON", "/usr/bin/python3"),
    Path.of("").toAbsolutePath.resolve("scripts/github_workflow.py").toString, token)

/** Explicit trusted wiring for embeddings and deterministic transport tests. */
@caps.rejectSafe
def configured(profile: String, python: String, helper: String, token: String): githubskill.GitHub =
  new GitHubImpl(profile, python, helper, token)
