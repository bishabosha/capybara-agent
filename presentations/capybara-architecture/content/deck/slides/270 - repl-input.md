---scala
(id = "repl-input", seconds = 75, layout = "standard")
---

## One complete tool call

```json
{"universe":"filesystem",
 "scala_code":"puts(filesystem.pwd())"}
```

The following slides show **every submitted command**, in order. Paths use this checkout. The generated UUID is normalized to `00000000_0000_0000_0000_000000000001`.

Packaging happens before this sequence on a cache miss. Host actions are labeled separately from commands submitted to `ReplDriver.run`.

[Open the full generated transcript](assets/source/repl-transcript.html)


{{stack footer}}

Source: [runExpression order](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L259-L300), [transcript generator](assets/source/skill-transcript.html)

{{end-stack}}

## Speaker notes

The transcript is emitted by skill-transcript using the actual SkillScaffold templates. It is a deterministic representation, not a fabricated interactive console capture. The slides format whitespace for readability; the linked transcript preserves the exact generated command text.
