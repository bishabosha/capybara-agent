---scala
(id = "publishing", seconds = 90, layout = "standard")
---

## GitHub demo snippet 2/2: commit, push and open a draft PR

```scala
val pushed = github.commit(topic,
  "Handle empty input and mixed whitespace",
  Seq("src/word-count.mjs"))
puts(github.createPullRequest(pushed,
  "Handle empty input and mixed whitespace", "Fixes #1"))
puts(github.checks(pushed))
```

`commit` resolves its public `using TextFiles` parameter to `coding`. It validates selected text, checks the expected head and advances the branch without force.

{{stack footer}}

Source: [full snippet](assets/source/github-snippet.html), [full demo transcript](assets/source/github-demo-transcript.html), [public API](assets/source/github-interface.html)

{{end-stack}}

## Speaker notes

This completes the checked-in demo snippet. Authentication belongs to GitHub’s trusted implementation; file authority belongs to the caller. Creating a commit/tree and advancing its ref is the API equivalent of publishing the selected changes.
