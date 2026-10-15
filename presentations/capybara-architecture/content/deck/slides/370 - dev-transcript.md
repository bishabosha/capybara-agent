---scala
(id = "dev-transcript", seconds = 75, layout = "standard")
---

## Step 3: inspect generated commands before execution

```sh
./mill run skill-transcript --skill skills/filesystem \
  --file examples/snippets/transcript-filesystem.scala
```

The transcript command loads metadata and uses `SkillScaffold.definition` and `entryPoint` to print the same method templates used by the runtime.

It includes JAR/predef commands, compiler-mode settings, generated methods, and labeled host actions. It compiles or executes nothing.

Next, run `skill-run`, then restart the agent and use the skill through `run_scala_code`.


{{stack footer}}

Source: [SkillTranscript](assets/source/skill-transcript.html), [shared scaffold](assets/source/skill-scaffold.html)

{{end-stack}}

## Speaker notes

The transcript regression checks command ordering, basic-universe behavior and builtin deduplication. This utility renders a normalized UUID rather than claiming to capture the next randomly assigned call identifier.
