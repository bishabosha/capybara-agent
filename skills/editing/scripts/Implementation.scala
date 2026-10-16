//> using file Interface.scala
//> using repository ivy2local
//> using dep "dev.capybara.skills::filesystem-api:0.1.0-SNAPSHOT"
package capybara.skills.editing.internal

import scala.language.experimental.captureChecking
import capybara.skills.filesystem.FileSystem

private class TextEditorImpl extends capybara.skills.editing.TextEditor {
  def replace(path: String, expected: String, next: String)(using files: FileSystem): Unit = {
    if files.read(path) != expected then throw IllegalStateException("CONTENT_CHANGED")
    files.write(path, next)
  }

  def edit(path: String)(transform: String -> String)(using files: FileSystem): Unit = {
    val before = files.read(path)
    replace(path, before, transform(before))
  }
}

@caps.rejectSafe
def impl(): capybara.skills.editing.TextEditor = new TextEditorImpl
