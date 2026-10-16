---scala
(id = "github-repl-invoke", seconds = 90, layout = "standard")
---

## Composed REPL 3b: pass contexts and invoke from the host

```scala
  try {
    agentCode_00000000_0000_0000_0000_000000000001(
      using __capybara_output, __capybara_filesystem, __capybara_github
    )
    true
  } catch {
    case scala.util.control.NonFatal(error) =>
      __capybara_output.printErr(builtins.baseReflectiveShowable(error))
      false
  }
}
```

The host reflects this entry method with its stdout/stderr streams, then resets the REPL to its initial state. Invocation and reset are host actions.

{{stack footer}}

Source: [complete composed transcript](assets/source/github-repl-transcript.html), [runtime sequence](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

This completes the method declaration from the previous slide. No extra submitted REPL command calls the entry method: ReplExec.invokeEntryPoint does that in host code. The full handout lists the exact host action label.
