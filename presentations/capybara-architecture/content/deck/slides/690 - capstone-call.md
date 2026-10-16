---scala
(id = "capstone-call", seconds = 75, layout = "standard")
---

## An accepted edit uses only its text argument

```scala
editing.edit("src/word-count.mjs") { text =>
  text.replace("return text.split(' ').length;",
    """const clean = text.trim();
  return clean === '' ? 0 : clean.split(/\s+/u).length;""")
}
puts(filesystem.read("src/word-count.mjs"))
```

The editor is generic. The path and language belong to this caller's example. Tests and GitHub publication are separate operations.

{{stack footer}}

Source: [editing interface](assets/source/editing-interface.html), [implementation](assets/source/editing-implementation.html), [integration tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

This same interface edits Markdown, Scala, JavaScript, JSON or other UTF-8 files. It does not embed a word-count project or testing policy.
