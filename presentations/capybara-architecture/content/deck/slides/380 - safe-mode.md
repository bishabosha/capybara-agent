---scala
(id = "safe-mode", seconds = 75, layout = "standard")
---

## Safe mode rejects concrete escape attempts

| Snippet | Repository regression expectation |
| --- | --- |
| `builtins.outputStreams(null, null)` | Error contains `rejectSafe` |
| `val value: Any = 1; value.asInstanceOf[String]` | Compilation returns `Result.Err` |
| `capybara.skills.filesystem.internal.impl().pwd()` | Error contains `rejectSafe` |

The public interface and generated snippet compile with `-language:experimental.safe`. The tests check specific rejected operations, rather than a blanket claim about every JVM API.

{{stack footer}}

Source: [the three tests](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/test/src/ReplExecSuite.scala#L134-L157), [interface compiler flag](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

Sources: build.mill.yaml, src/ReplExec.scala compileSkill and setSafeMode, test/src/ReplExecSuite.scala. Scala reference: https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/safe.html . Safe mode activates capture/separation checking and constrains access to untracked effects and unsafe constructs. It is experimental. Avoid presenting it as a production security proof or a JVM/OS sandbox. Trusted code remains part of the trusted computing base.
