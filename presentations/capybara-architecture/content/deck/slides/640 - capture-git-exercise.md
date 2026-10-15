---scala
(id = "capture-git-exercise", seconds = 90, layout = "standard")
---

## Counterexamples: captured effects fail before an edit

```scala
coding.edit("Greeting.scala") { text =>
  puts(text) // captures output
  text
}
```

```scala
coding.edit("src/word-count.mjs") { text =>
  github.lookupIssue(1).body // captures GitHub
}
```

Both callbacks must have type `String -> String`. The compiler rejects their retained capability references.

{{stack footer}}

Source: [counterexample snippets](assets/source/capture-examples.html), [regression tests](assets/source/github-tests.html)

{{end-stack}}

## Speaker notes

Run coding-capture-output.scala with skill-run --skill coding and github-capture-in-edit.scala with --skill github. The files remain unchanged. GitHub and output are valid outside the pure callback.
