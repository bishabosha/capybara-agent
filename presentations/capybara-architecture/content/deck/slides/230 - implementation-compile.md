---scala
(id = "implementation-compile", seconds = 75, layout = "standard")
---

## Implementation packaging: link against the checked interface

```sh
scala --power package -S 3.10.0 --library -f \
  -o .agent-runtime/temp/filesystem/impl.jar \
  --extra-jar .agent-runtime/temp/filesystem/sigs.jar \
  .agent-runtime/temp/filesystem/Implementation.scala
```

The staging step combines interface and implementation dependency directives, then removes `//> using file` / `files` directives. Types come from `sigs.jar`, avoiding a second copy of the interface.

This command has **no `experimental.safe` flag**. Implementation code and capability factories are trusted.


{{stack footer}}

Source: [directive processing and packaging](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

Trusted implementations can import experimental.captureChecking themselves, as the bundled skills do. That import is distinct from subjecting the implementation to safe mode. Dependency directives affect Scala CLI compilation; they do not promise all transitive libraries become runtime REPL dependencies.
