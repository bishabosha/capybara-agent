---scala
(id = "atomic-skills", seconds = 75, layout = "standard")
---

## Compose authority without combining implementations

```json
{"universes": ["editing", "github"], "scala_code": "..."}
```

`editing` handles local text. `github` handles remote GitHub operations. The harness supplies separate capabilities in the same safe method.

A public method can require another skill's checked API as a contextual parameter. Its caller supplies that authority.

{{stack footer}}

Source: [dependency resolution](assets/source/skills-loader.html), [local publication](assets/source/skill-artifacts.html), [composition tests](assets/source/composition-tests.html)

{{end-stack}}

## Speaker notes

These are implemented mechanisms validated through the real safe harness and local Ivy publication. Factory code is trusted. The generated snippet body remains in safe mode.
