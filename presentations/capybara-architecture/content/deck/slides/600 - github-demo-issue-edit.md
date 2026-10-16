---scala
(id = "github-demo-issue-edit", seconds = 90, layout = "standard")
---

## GitHub demo snippet 1/2: look up, branch and implement

```scala
puts(github.lookupIssue(1))
val topic = github.createBranch("capybara/issue-1")
editing.edit("src/word-count.mjs") { source =>
  source.replace("return text.split(' ').length;",
    """const clean = text.trim();
  return clean === '' ? 0 : clean.split(/\s+/u).length;""")
}
puts(filesystem.read("src/word-count.mjs"))
```

This is the first half of `examples/snippets/github-lab.scala`. Its callback uses only the supplied source text.

{{stack footer}}

Source: [full snippet](assets/source/github-snippet.html), [full demo transcript](assets/source/github-demo-transcript.html)

{{end-stack}}

## Speaker notes

The second half follows on the next slide. Both halves belong to one checked method and one skill-run invocation. The full generated command transcript for this demo is also linked.
