---scala
(id = "universe", seconds = 75, layout = "standard")
---

## The selected universe adds explicit parameters

```json
{"universe":"filesystem",
 "scala_code":"puts(filesystem.list(filesystem.pwd()))"}
```

| Selected universe | Supplied contextual parameters |
| --- | --- |
| `basic` | `output` |
| `filesystem` | `output`, `filesystem` |
| `secrets` | `output`, `secretAccess` |

`runCode` always loads builtins. It adds only the selected skill's JARs and bindings, with no general universe composition.

{{stack footer}}

Source: [runCode](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L339-L350), [binding merge](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/SkillScaffold.scala#L5-L15)

{{end-stack}}

## Speaker notes

Source: src/Skills.scala, src/ReplExec.scala and the three manifests. Basic skips a skill-specific implementation JAR. Builtins is always present and is deduplicated when selected directly. An unknown universe becomes a tool error. A universe name alone does not enforce repository paths or network destinations. Those restrictions belong in the implementation.
