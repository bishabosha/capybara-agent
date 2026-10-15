---scala
(id = "dev-skill", seconds = 75, layout = "standard")
---

## Develop a skill in its own directory

```text
skills/coding/
  SKILL.md
  scripts/Manifest.scala
  scripts/Interface.scala
  scripts/Implementation.scala
```

`SKILL.md` and the manifest use the same universe name. The interface defines the model-facing capability. The implementation supplies a trusted factory marked `@caps.rejectSafe`.

There is no extra Mill module or duplicated dependency list. Keep source dependency directives on the original skill files.


{{stack footer}}

Source: [actual coding interface](assets/source/coding-interface.html), [actual manifest](assets/source/coding-manifest.html)

{{end-stack}}

## Speaker notes

The runtime loader discovers directories at startup. Restart the agent after adding a new skill. The coding capability and helper are implemented in this working tree. Its helper remains trusted application code, not repository-supplied Python.
