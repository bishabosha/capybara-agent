package githubskill

import scala.language.experimental.captureChecking

case class Issue(number: Int, title: String, body: String, url: String)
case class IssueSummary(number: Int, title: String, url: String)
case class Workspace(issue: Int, branch: String, baseCommit: String)
case class TestResult(status: String)
case class Publication(branch: String, commit: String)
case class PullRequest(number: Int, url: String)
case class CheckSummary(commit: String, status: String)

/** Repository, paths, credentials and test command are fixed by trusted host configuration.
  * There is deliberately no token getter, general HTTP client, shell or arbitrary Git API.
  */
trait GitHub extends caps.SharedCapability {
  def listIssues(page: Int): Seq[IssueSummary]
  def lookupIssue(number: Int): Issue
  def start(number: Int): Workspace
  def files(): Seq[String]
  def read(path: String): String
  def replace(path: String, expected: String, next: String): Unit
  /** The transformation cannot retain output, GitHub or another capability. */
  def transform(path: String)(f: String -> String): Unit
  def diff(): String
  def test(): TestResult
  def push(message: String): Publication
  def createPullRequest(title: String, body: String): PullRequest
  def checks(): CheckSummary
}
