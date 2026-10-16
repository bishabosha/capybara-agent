---scala
(id = "github-repl-load", seconds = 90, layout = "standard")
---

## Composed REPL 1: load checked APIs, then implementations

```scala
:jar .agent-runtime/interface/capybara-builtins/sigs.jar
:jar .agent-runtime/interface/capybara-builtins/impl.jar
import system_impl.builtins
import builtins.given
import builtins.{puts, putsErr}
:jar .agent-runtime/interface/filesystem/sigs.jar
:jar .agent-runtime/interface/github/sigs.jar
:jar .agent-runtime/interface/filesystem/impl.jar
:jar .agent-runtime/interface/github/impl.jar
```

Paths are relative here for readability. The linked transcript retains the exact paths and import statements emitted by the harness.

{{stack footer}}

Source: [complete composed transcript](assets/source/github-repl-transcript.html), [runtime sequence](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

API jars are loaded in dependency order. API-only dependencies do not cause implementation loading or binding construction. This manifest explicitly requests the editing runtime context.
