package capybara.skills.filesystem

import scala.language.experimental.captureChecking

/** File authority restricted to one host-selected workspace. Paths are workspace-relative. */
trait FileSystem extends caps.SharedCapability {
  def pwd(): String
  def list(path: String): Seq[String]
  def read(path: String): String
  def write(path: String, text: String): Unit
}
