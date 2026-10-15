---scala
(id = "dev-snippet", seconds = 75, layout = "standard")
---

## Step 2: execute a snippet without a model

```sh
./mill run skill-run --skill skills/filesystem \
  --file examples/snippets/transcript-filesystem.scala
```

The snippet file contains only:

```scala
puts(filesystem.pwd())
```

`SkillRunner` loads builtins and the selected skill, then calls `ReplExec.runCode`. It emits captured stdout/stderr and exits nonzero on compilation or execution failure.

The agent uses the same capability wiring and execution path.


{{stack footer}}

Source: [SkillRunner.run](assets/source/skill-runner.html)

{{end-stack}}

## Speaker notes

This replaces the earlier pre-architecture exercise. It is a development workflow demonstration. Run new Scala compilation/tests through MCP as instructed in AGENTS.md; these are the user-facing CLI entry points. skill-run does not initialize a provider or contact a model.
