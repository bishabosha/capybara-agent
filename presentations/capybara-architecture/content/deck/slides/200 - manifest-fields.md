---scala
(id = "manifest-fields", seconds = 75, layout = "standard")
---

## A malformed binding fails before snippet compilation

```scala
require(all.map(_.name).distinct.size == all.size,
  "Duplicate capability parameter names in manifests")
for binding <- all do
  require(binding.name.matches("[a-zA-Z_][a-zA-Z0-9_]*"),
    s"Invalid capability name: ${binding.name}")
```

The same function rejects empty types/factory values and requires the builtin `output` binding. These scaffold checks run before snippet type checking.

Keep skill `name` and `universe` equal: the cache writes by name, while JAR loading reads by universe.

{{stack footer}}

Source: [binding validation](assets/source/skill-scaffold.html), [JAR loading](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

Source excerpt: SkillScaffold.bindings. Empty types and values are rejected on line 13, and the output requirement is line 14. The name/universe convention is checked by SkillRunner.run, but not Skills.loadFrom. compileSkill writes under skill.name while loadSkill uses skill.universe. The scaffold requires nonempty strings, not an independent factory-code security analysis. Manifest authors remain trusted.
