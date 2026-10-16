---scala
(id = "github-repl-safe", seconds = 90, layout = "standard")
---

## Composed REPL 2: compile with three visible capabilities

```scala
:settings -language:experimental.safe

def agentCode_00000000_0000_0000_0000_000000000001(
    using output: builtins.OutputStreams,
    filesystem: capybara.skills.filesystem.FileSystem, github: capybara.skills.github.GitHub
): Unit = {
  val topic = github.branch("capybara/issue-1")
  val pushed = github.commit(topic, "Fix whitespace",
    Seq("src/word-count.mjs"))
  puts(pushed)
  ()
}
```

The compiler resolves `commit`’s `FileSystem` requirement to `filesystem`. The host clears language features before applying the setting.

{{stack footer}}

Source: [complete composed transcript](assets/source/github-repl-transcript.html), [public API](assets/source/github-interface.html)

{{end-stack}}

## Speaker notes

Projection formatting only: the actual method is rendered by SkillScaffold.definition, with the normalized identifier used in the handout. No hidden file capability is obtained from GitHub.
