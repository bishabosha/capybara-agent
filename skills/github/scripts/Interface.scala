//> using repository ivy2local
//> using dep "dev.capybara.skills::filesystem-api:0.1.0-SNAPSHOT"
package capybara.skills.github

import scala.language.experimental.captureChecking

case class Issue(number: Int, title: String, body: String, url: String)
case class IssueSummary(number: Int, title: String, url: String)
case class Branch(name: String, commit: String)
case class PullRequest(number: Int, url: String)
case class CheckSummary(commit: String, status: String)

/** GitHub authority only. Local text editing is a separate editing capability. */
trait GitHub extends caps.SharedCapability {
  def listIssues(page: Int): Seq[IssueSummary]
  def lookupIssue(number: Int): Issue
  def branch(name: String): Branch
  /** Create from the base, or reuse an existing ref only if its head still equals the base. */
  def createBranch(name: String): Branch
  /** Requires the caller's file authority to read selected paths before committing and pushing. */
  def commit(branch: Branch, message: String, paths: Seq[String])(using files: capybara.skills.filesystem.FileSystem): Branch
  def createPullRequest(branch: Branch, title: String, body: String): PullRequest
  def checks(branch: Branch): CheckSummary
}
