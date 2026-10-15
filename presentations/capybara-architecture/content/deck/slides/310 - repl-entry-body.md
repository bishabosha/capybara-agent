---scala
(id = "repl-entry-body", seconds = 60, layout = "standard")
---

## REPL 3b: invoke the checked method and report failure

{{stack transcript}}

```scala
  try {
    agentCode_00000000_0000_0000_0000_000000000001(
      using __capybara_output, __capybara_filesystem
    )
    true
  } catch {
    case scala.util.control.NonFatal(error) =>
      __capybara_output.printErr(builtins.baseReflectiveShowable(error))
      false
  }
}
```

This completes the declaration from the previous slide. Its body supplies explicit capabilities and maps an exception to stderr and `false`. The host invokes the entry point afterward.

{{end-stack}}

{{stack footer}}

Source: [complete transcript](assets/source/repl-transcript.html), [runtime sequence](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L248-L300)

{{end-stack}}

## Speaker notes

This continues the exact same harnessEntry declaration. It is not a second REPL command. All statements are retained, with whitespace formatted for projection. The host reflection call appears on the following slide. The complete unformatted command is linked below.
