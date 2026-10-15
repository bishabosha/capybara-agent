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

Source: [binding validation](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/SkillScaffold.scala#L5-L15), [JAR loading](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L248-L256)

{{end-stack}}

## Speaker notes

Source excerpt: SkillScaffold.bindings. Empty types and values are rejected on line 13, and the output requirement is line 14. The name/universe convention is checked by SkillRunner.run, but not Skills.loadFrom. compileSkill writes under skill.name while loadSkill uses skill.universe. The scaffold requires nonempty strings, not an independent factory-code security analysis. Manifest authors remain trusted.
