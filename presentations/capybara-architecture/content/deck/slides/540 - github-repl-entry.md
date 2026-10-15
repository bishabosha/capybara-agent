---scala
(id = "github-repl-entry", seconds = 90, layout = "standard")
---

## Composed REPL 3a: construct the separate capabilities

```scala
:settings -language:experimental.captureChecking

def harnessEntry_00000000_0000_0000_0000_000000000001(
    __harnessStdout: java.io.PrintStream,
    __harnessStderr: java.io.PrintStream
): Boolean = {
  given __capybara_output: builtins.OutputStreams =
    builtins.outputStreams(__harnessStdout, __harnessStderr)
  given __capybara_coding: editing.TextFiles =
    editing.internal.impl()
  given __capybara_github: githubskill.GitHub =
    githubskill.internal.impl()
```

The host clears language features before selecting capture checking. This method continues on the next slide.

{{stack footer}}

Source: [complete composed transcript](assets/source/github-repl-transcript.html), [scaffold templates](assets/source/skill-scaffold.html)

{{end-stack}}

## Speaker notes

The GitHub constructor receives no TextFiles. The checked method receives independent capability arguments. These are actual factory expressions from the manifests.
