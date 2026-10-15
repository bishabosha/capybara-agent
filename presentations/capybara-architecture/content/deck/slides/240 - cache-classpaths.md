---scala
(id = "cache-classpaths", seconds = 75, layout = "standard")
---

## Three classpaths participate in one call

| Boundary | Available classes |
| --- | --- |
| Interface packaging | Scala/JDK plus its declared dependencies |
| Implementation packaging | Its declared dependencies plus `sigs.jar` |
| Execution REPL | Scala library loader, platform Java classes, then explicitly loaded skill JARs |

The REPL loader filters the agent JVM classpath to Scala library URLs. A library used to compile `impl.jar` is **not automatically loaded** into that REPL.

The workshop adapter therefore uses only Scala and JDK APIs, even though the host agent has `os-lib` and `ujson`.


{{stack footer}}

Source: [isolated loader and initial settings](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L63-L86), [JAR loading](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L248-L256)

{{end-stack}}

## Speaker notes

A real integration run exposed NoClassDefFoundError for ujson when the first adapter reused host dependencies. The final adapter is JDK-only. Compilation success and execution classpath completeness are separate concerns. Additional runtime dependency loading would require an explicit runtime change; this workshop does not silently widen the REPL classpath.
