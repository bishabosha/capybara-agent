//> using repository ivy2local
//> using dep "dev.capybara.skills::filesystem-api:0.1.0-SNAPSHOT"
package capybara.skills.editing

import scala.language.experimental.captureChecking
import capybara.skills.filesystem.FileSystem

/** Text transformations; all file authority comes from the caller's filesystem capability. */
trait TextEditor extends caps.SharedCapability {
  /** Replace only when the complete current text equals expected. */
  def replace(path: String, expected: String, next: String)(using files: FileSystem): Unit
  /** The callback may transform text but cannot retain capabilities. */
  def edit(path: String)(transform: String -> String)(using files: FileSystem): Unit
}
