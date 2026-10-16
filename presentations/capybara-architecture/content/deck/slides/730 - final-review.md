---scala
(id = "final-review", seconds = 75, layout = "standard")
---

## Evidence from the composed architecture

| Boundary | Executed evidence |
| --- | --- |
| Multiple atomic skills share one safe call | Coding edit followed by GitHub commit/PR |
| A dependency uses another skill's safe API | Local editing-api publication and public using FileSystem |
| Pure callbacks retain no extra capabilities | Rejected output and cross-skill callbacks |
| Credentials stay behind GitHub's interface | No getter, direct-access rejections, release checks |
| Editing is language-independent | Scala, Markdown, JSON and new text files |

Neither editing nor GitHub operations launches a Python helper or process sandbox.

{{stack footer}}

Source: [composition tests](assets/source/composition-tests.html), [GitHub tests](assets/source/github-tests.html), [editing tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

Tests use the real compiler, local publication and JDK HTTP serialization, with a local GitHub fixture and synthetic credentials. No authenticated live push occurs in automated validation. Capability references are checked; arbitrary strings are not automatically classified as confidential.
