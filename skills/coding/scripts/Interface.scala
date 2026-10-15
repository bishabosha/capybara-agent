package editing

import scala.language.experimental.captureChecking

/** Text-file authority supplied by the harness for one workspace. */
trait TextFiles extends caps.SharedCapability {
  def list(path: String): Seq[String]
  def read(path: String): String
  def write(path: String, text: String): Unit
  /** Replace only when the complete current text equals expected. */
  def replace(path: String, expected: String, next: String): Unit
  /** The callback may transform text but cannot retain capabilities. */
  def edit(path: String)(transform: String -> String): Unit
}
