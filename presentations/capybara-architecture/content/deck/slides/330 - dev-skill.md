---scala
(id = "dev-skill", seconds = 75, layout = "standard")
---

## Develop a skill in its own directory

```text
skills/editing/
  SKILL.md
  scripts/Manifest.scala
  scripts/Interface.scala
  scripts/Implementation.scala
```

`SKILL.md` and the manifest use the same universe name. The interface defines the model-facing capability. The implementation supplies a trusted factory marked `@caps.rejectSafe`.

There is no extra Mill module or duplicated dependency list. Keep source dependency directives on the original skill files.


{{stack footer}}

Source: [actual editing interface](assets/source/editing-interface.html), [actual manifest](assets/source/editing-manifest.html)

{{end-stack}}

## Speaker notes

The runtime loader discovers directories at startup. Restart the agent after adding a new skill. The editing implementation uses the JDK directly. Dependencies import safe skill API types with using dep. Manifest requires separately requests runtime contexts; public method signatures declare their use.
