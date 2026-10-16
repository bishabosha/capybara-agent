---scala
(id = "repl-entry", seconds = 60, layout = "standard")
---

## REPL 3a: trusted signature and capability wiring

{{stack transcript}}

```scala
:settings -language:experimental.captureChecking

def harnessEntry_00000000_0000_0000_0000_000000000001(
    __harnessStdout: java.io.PrintStream,
    __harnessStderr: java.io.PrintStream
): Boolean = {
  given __capybara_output: builtins.OutputStreams =
    builtins.outputStreams(__harnessStdout, __harnessStderr)
  given __capybara_filesystem: capybara.skills.filesystem.FileSystem = capybara.skills.filesystem.internal.impl()
```

The host clears language features, then selects capture checking. Trusted factories supply the two capability arguments. **The same method continues on the next slide.**

{{end-stack}}

{{stack footer}}

Source: [complete transcript](assets/source/repl-transcript.html), [runtime sequence](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

This is the first part of one submitted method declaration, not a separate REPL submission. The opening brace remains open. The next slide shows every remaining statement and the closing brace. Display whitespace is formatted for projection; the linked transcript preserves exact generated commands.
