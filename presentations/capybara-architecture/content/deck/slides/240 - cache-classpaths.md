---scala
(id = "cache-classpaths", seconds = 75, layout = "standard")
---

## Safe API dependencies connect independent skills

| Stage | Classpath |
| --- | --- |
| Interface compilation | Scala/JDK and declared safe API dependencies |
| Implementation compilation | Its own `sigs.jar` and required safe APIs |
| Execution REPL | Builtins, dependency APIs and granted skills' implementation JARs |

The GitHub public API imports the editing API. Its commit method requires the caller’s `TextFiles` context. It has no separate filesystem implementation or process runner.

{{stack footer}}

Source: [safe harness](assets/source/repl-exec.html), [capability wiring](assets/source/skill-scaffold.html)

{{end-stack}}

## Speaker notes

The host classpath is not inherited. Both skill implementations use Scala/JDK APIs; the safe editing artifact is resolved through local Ivy. Implementation factories are not included in the published interface artifact.
