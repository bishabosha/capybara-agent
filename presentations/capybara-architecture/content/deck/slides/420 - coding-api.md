---scala
(id = "coding-api", seconds = 75, layout = "standard")
---

## Generic text tools form one small capability

```scala
trait TextFiles extends caps.SharedCapability {
  def list(path: String): Seq[String]
  def read(path: String): String
  def write(path: String, text: String): Unit
  def replace(path: String, expected: String, next: String): Unit
  def edit(path: String)(transform: String -> String): Unit
}
```

The harness supplies `coding: editing.TextFiles`. There are no project-specific filenames, language tools, test commands or process sandbox.

{{stack footer}}

Source: [editing interface](assets/source/coding-interface.html), [implementation](assets/source/coding-implementation.html), [integration tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

replace compares the complete expected contents before updating. edit reads a file, applies a pure text function and uses expected-content replacement. The implementation uses the JDK directly. All ordinary text files in the host-selected workspace are accessible.
