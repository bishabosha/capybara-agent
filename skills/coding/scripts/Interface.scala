package workshop

import scala.language.experimental.captureChecking

/** A fixed summary. The test process cannot return its logs to the model. */
case class TestSummary(status: String)

trait Coding extends caps.SharedCapability {
  def read(path: String): String
  def replace(path: String, expected: String, next: String): Unit
  /** Transform approved source with a callback that cannot retain any capability. */
  def transformSource(f: String -> String): Unit
  def test(): TestSummary
  def diff(): String
  def sandboxChecks(): Seq[String]
}
