---scala
(id = "skill-files", seconds = 75, layout = "standard")
---

## What the model sees and what stays trusted

| File | Loader and prompt behavior |
| --- | --- |
| `SKILL.md` | Parses YAML `name` and `description` |
| `Manifest.scala` | Parses `Manifest` as a named tuple |
| `Interface.scala` | Includes public source in the model prompt |
| `Implementation.scala` | Loads for execution, excludes source from the prompt |

`renderPrompt` includes binding **names and types**, but excludes their factory expressions. The Markdown body of `SKILL.md` also stays out of this prompt.

{{stack footer}}

Source: [loadFrom](assets/source/skills-loader.html), [renderPrompt](assets/source/skills-loader.html)

{{end-stack}}

## Speaker notes

Source: src/Skills.scala. The manifest loader parses the Manifest declaration with scala-object-notation. It does not simply execute the entire manifest file. Binding factory expressions become executable trusted harness code later. Skill authors and reviewers control that trusted configuration.
