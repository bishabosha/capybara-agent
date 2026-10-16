---scala
(id = "editing-api", seconds = 75, layout = "standard")
---

## Text editing depends on filesystem authority

```scala
// skills/editing/Interface.scala
import capybara.skills.filesystem.FileSystem
trait TextEditor extends caps.SharedCapability {
  def replace(path: String, expected: String, next: String)
      (using files: FileSystem): Unit
  def edit(path: String)(transform: String -> String)
      (using files: FileSystem): Unit
}
```

The harness supplies `editing: capybara.skills.editing.TextEditor`. There are no project-specific filenames, language tools, test commands or process sandbox.

{{stack footer}}

Source: [editing interface](assets/source/editing-interface.html), [implementation](assets/source/editing-implementation.html), [integration tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

replace compares the complete expected contents before updating. edit reads a file, applies a pure text function and uses expected-content replacement. The filesystem implementation owns workspace validation and JDK I/O. Editing uses only its public safe API. Selecting editing automatically supplies filesystem.
