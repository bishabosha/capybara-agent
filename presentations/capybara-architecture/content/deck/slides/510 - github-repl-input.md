---scala
(id = "github-repl-input", seconds = 90, layout = "standard")
---

## Composed call: commit selected local text

```json
{"universes": ["github"], "scala_code": "..."}
```

```scala
val topic = github.branch("capybara/issue-1")
val pushed = github.commit(topic, "Fix whitespace",
  Seq("src/word-count.mjs"))
puts(pushed)
```

The manifest’s `requires = Vector("coding")` supplies `coding` automatically. The following slides show the complete generated commands for this call.

{{stack footer}}

Source: [complete composed transcript](assets/source/github-repl-transcript.html), [scaffold templates](assets/source/skill-scaffold.html)

{{end-stack}}

## Speaker notes

This example assumes that the topic branch already exists. It illustrates one tool call, not the full demo. The full demo later creates the branch and edits the file.
