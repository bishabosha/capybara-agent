---scala
(id = "repl-safe", seconds = 75, layout = "standard")
---

## REPL 2: compile the generated method in safe mode

{{stack transcript}}

```scala
:settings -language:experimental.safe

def agentCode_00000000_0000_0000_0000_000000000001(
    using output: builtins.OutputStreams,
    filesystem: fs.Filesystem
): Unit = {
  puts(filesystem.pwd())
  ()
}
```

Before this settings command, the host clears the compiler language-feature list. The body is the model-supplied `scala_code`, followed by the scaffold’s `()` result discard.

{{end-stack}}

{{stack footer}}

Source: [complete transcript](assets/source/repl-transcript.html), [runtime sequence](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

All commands and statements are shown. Whitespace and line breaks are formatted for projection; the linked transcript retains the exact generated text. The per-call UUID is normalized; paths reflect the authoring checkout.
