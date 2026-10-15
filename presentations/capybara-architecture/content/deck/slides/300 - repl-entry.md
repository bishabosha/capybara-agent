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
  val __capybara_output: builtins.OutputStreams =
    builtins.outputStreams(__harnessStdout, __harnessStderr)
  val __capybara_filesystem: fs.Filesystem = fs.internal.impl()
```

The host clears language features, then selects capture checking. Trusted factories supply the two capability arguments. **The same method continues on the next slide.**

{{end-stack}}

{{stack footer}}

Source: [complete transcript](assets/source/repl-transcript.html), [runtime sequence](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L248-L300)

{{end-stack}}

## Speaker notes

This is the first part of one submitted method declaration, not a separate REPL submission. The opening brace remains open. The next slide shows every remaining statement and the closing brace. Display whitespace is formatted for projection; the linked transcript preserves exact generated commands.
