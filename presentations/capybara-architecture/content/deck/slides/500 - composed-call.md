---scala
(id = "composed-call", seconds = 75, layout = "standard")
---

## One checked body receives independent capabilities

```scala
def agentCode(
    using output: builtins.OutputStreams,
    coding: editing.TextFiles,
    github: githubskill.GitHub
): Unit = {
  // local edits and remote operations can compose here
}
```

A callback typed `String -> String` still cannot retain output, coding or GitHub. Adding skills does not relax its capture set.

{{stack footer}}

Source: [dependency resolution](assets/source/skills-loader.html), [local publication](assets/source/skill-artifacts.html), [composition tests](assets/source/composition-tests.html)

{{end-stack}}

## Speaker notes

These are implemented mechanisms validated through the real safe harness and local Ivy publication. Factory code is trusted. The generated snippet body remains in safe mode.
