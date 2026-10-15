---scala
(id = "test-sandbox", seconds = 75, layout = "standard")
---

## The interface and safe harness control file effects

```scala
def agentCode(
    using output: builtins.OutputStreams,
    coding: editing.TextFiles
): Unit = {
  coding.edit("README.md")(_.replace("Old", "New"))
  puts(coding.read("README.md"))
}
```

The snippet compiles in safe mode. Trusted wiring supplies the file capability. Its implementation performs the declared JDK file operations directly.

{{stack footer}}

Source: [safe harness](assets/source/repl-exec.html), [capability wiring](assets/source/skill-scaffold.html)

{{end-stack}}

## Speaker notes

This slide replaces the separate Node/process sandbox design. The generic editor runs no process, shell, Python helper or OS sandbox. A capability method authorizes the effects it exposes. The pure callback may transform strings but may not retain output, file or GitHub capabilities.
