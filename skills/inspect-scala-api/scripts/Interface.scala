package capybara.skills.inspectscalaapi

import scala.language.experimental.captureChecking

/** Read-only documentation for installed, prepared skill APIs. */
trait ScalaApiInspector extends caps.SharedCapability {
  def get(skill: String, symbol: String): String
  def list(skill: String, packageOrType: String): String
  def search(skill: String, query: String): String
}
