---scala
(id = "dependency-factory", seconds = 75, layout = "standard")
---

## File authority is explicit in the public API

```scala
// GitHub's public safe interface
def commit(branch: Branch, message: String, paths: Seq[String])
    (using files: capybara.skills.filesystem.FileSystem): Branch

// Manifest.scala: automatically supply the caller's context
requires = Vector("filesystem")
```

`using dep` supplies API types. `requires` supplies a runtime context. The call site passes `FileSystem`; the GitHub factory holds no file capability.

{{stack footer}}

Source: [GitHub manifest](assets/source/github-manifest.html), [public API](assets/source/github-interface.html), [dependency resolution](assets/source/skills-loader.html), [local publication](assets/source/skill-artifacts.html), [composition tests](assets/source/composition-tests.html)

{{end-stack}}

## Speaker notes

These are implemented mechanisms validated through the real safe harness and local Ivy publication. Factory code is trusted. The generated snippet body remains in safe mode.
