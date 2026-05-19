---
name: staged-skill-authoring
description: Stages proposed Capybara skills in a session-scoped workspace. Use when the current available universes cannot accomplish the user's requested workflow and a new skill should be proposed.
---

# Staged Skill Authoring

Use this skill only to propose a new Capybara skill when the active universes cannot perform the user's requested workflow.

Stage exactly one skill directory unless the user asks for more. The directory must contain only:

```text
skill-name/
  SKILL.md
  scripts/
    Interface.scala
    Implementation.scala
    Manifest.scala
```

## Required File Formats

### SKILL.md

Use YAML frontmatter with `name` and `description`, then concise instructions.

```markdown
---
name: skill-name
description: One sentence explaining when this skill should be used.
---

# Skill Name

Brief workflow instructions for the agent.
```

### scripts/Interface.scala

The interface file must begin with Scala CLI using directives before the package declaration.

```scala
//> using options -preview
package packageName

trait CapabilityName {
  // Public API visible to generated agent code.
}
```

Keep this file to safe public signatures. Do not expose constructors, root paths, shell commands, or host filesystem types unless the skill intentionally grants that capability.

### scripts/Implementation.scala

The implementation file must begin with a using directive for the interface file.

```scala
//> using file Interface.scala
package packageName.internal

import scala.language.experimental.{captureChecking, separationChecking}
import caps.assumeSafe

private object PrivateCapability extends packageName.CapabilityName {
  // Implementation hidden behind the interface.
}

@assumeSafe
val impl: packageName.CapabilityName = PrivateCapability
```

Do not repeat `Interface.scala` contents here. Place dependencies and other Scala CLI directives at the top, before the `package` line.

### scripts/Manifest.scala

The manifest must be exactly a top-level named tuple assigned to `val Manifest`.

```scala
val Manifest = (
  universe = "skill-universe",
  api = "val capability: packageName.CapabilityName = ...",
  predef = "val capability: packageName.CapabilityName = packageName.internal.impl"
)
```

The required named tuple fields are:

- `universe`: the string identifier passed to `run_scala_code`.
- `api`: concise instructions shown to the agent, including the preloaded binding shape.
- `predef`: Scala setup code executed before agent code; usually binds the public value to `*.internal.impl`.

The manifest must not define a case class, object, method, JSON value, or YAML value. It must be parseable as Scala object notation by the runtime loader.

## Staging Rules

Use `stagedSkills.writeSkill(...)` for normal creation. Use `write`, `read`, `list`, `makeDirectories`, and `exists` only to inspect or adjust files inside the staged workspace.

All paths are relative to the staged workspace. Absolute paths, `..` parent segments, and symbolic links are rejected. The workspace directory is created lazily only by mutating operations.
