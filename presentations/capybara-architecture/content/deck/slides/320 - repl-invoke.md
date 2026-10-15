---scala
(id = "repl-invoke", seconds = 75, layout = "standard")
---

## Host invocation, captured result and reset

```scala
invokeEntryPoint(entryName, stdout.stream, stderr.stream)
```

The host reflects the entry method with two `PrintStream` arguments. There is **no additional REPL evaluation command** for the invocation.

```json
{"special.success":"."}
{"success":true,"stdout":["."],"stderr":[]}
```

The first JSON line comes from `puts`. The second is the decoded tool result. Finally, `stateless` resets the REPL and clears diagnostics. The result enters conversation history.


{{stack footer}}

Source: [reflective invocation](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L113-L126), [reset and decode](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L303-L310)

{{end-stack}}

## Speaker notes

Filesystem.pwd wraps java.nio.file.Paths.get("."), so its display string is a dot rather than an invented absolute directory. The two JSON objects belong to different stages, not the same stdout stream. Exceptions are caught in trusted wiring and earlier channel output is preserved.
