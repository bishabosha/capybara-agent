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

Stage a narrow workflow capability, not a general escape hatch. Do not propose generic filesystem, shell, process, network, or operating-system automation skills just to bypass the current universe. If a workflow needs access to an external resource, the public API should expose task-level operations and the implementation should own any harness-entitled handles internally.

## Required File Formats

### SKILL.md

Use `stagedSkills.writeSkillMarkdown(...)` to create this file. It must use YAML frontmatter with `name` and `description`, then concise instructions.

```markdown
---
name: skill-name
description: One sentence explaining when this skill should be used.
---

# Skill Name

Brief workflow instructions for the agent.
```

### scripts/Interface.scala

Use `stagedSkills.writeInterface(...)` to create this file. It must begin with Scala CLI using directives before the package declaration. The staged workspace validates it by compiling `Interface.scala` in experimental safe mode before writing it.

```scala
//> using options -preview
package packageName

trait CapabilityName {
  // Public API visible to generated agent code.
}
```

Keep this file to safe public signatures. Do not expose constructors, root paths, absolute paths, path parsers such as `fromString`, path joiners, shell commands, process APIs, or host filesystem types such as `java.nio.file.Path`, `java.io.File`, or `os.Path`. Do not add companion `api`/`predef` values or manifest helpers here.

### scripts/Implementation.scala

Use `stagedSkills.writeImplementation(...)` to create this file. It must include a using directive for the interface file before the package declaration. The staged workspace validates it by compiling the current `Interface.scala` to a signatures jar, then compiling `Implementation.scala` against that jar before writing it.

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

The implementation must expose exactly one public value: `@assumeSafe val impl: packageName.CapabilityName = ...`. The explicit type must be the public API interface from `Interface.scala`; do not leave it inferred, because inference can leak private implementation types into the generated interface. Do not expose `impl` as a `def`, object, class, or untyped value.

The implementation package must end with `.internal`, and the manifest predef will bind to `<implementationPackage>.impl`. Do not repeat `Interface.scala` contents here. Place dependencies and other Scala CLI directives at the top, before the `package` line. Do not use `java.nio.file.Paths.get`; the implementation should work from harness-owned handles or narrowly scoped internal locations.

### scripts/Manifest.scala

Do not author this file by hand. Use `stagedSkills.writeManifest(name, universe, bindingName, interfaceFqn, implementationPackage)`; the staged workspace implementation renders the file.

Pass only these fields:

- `name`: staged skill directory name.
- `universe`: the string identifier future calls pass to `run_scala_code`.
- `bindingName`: the single lower-camel-case value already in scope for future agent code.
- `interfaceFqn`: the fully-qualified public interface type from `Interface.scala`.
- `implementationPackage`: the fully-qualified implementation package, which must end in `.internal`.

The rendered manifest will be exactly a top-level named tuple assigned to `val Manifest`.

```scala
val Manifest = (
  universe = "skill-universe",
  api = "val capability: packageName.CapabilityName = ...",
  predef = "val capability: packageName.CapabilityName = packageName.internal.impl"
)
```

The required named tuple fields are:

- `universe`: the string identifier passed to `run_scala_code`.
- `api`: generated single-line calling convention shown to the agent.
- `predef`: generated Scala setup code that binds the public value to `*.internal.impl`.

The rendered `api` field is a single-line calling convention generated from `bindingName` and `interfaceFqn`, for example `val capability: packageName.CapabilityName = ...`. Do not pass a trait body, imports, package declarations, or guide text as manifest API.

The manifest must not define a case class, object, method, JSON value, or YAML value. It must be parseable as Scala object notation by the runtime loader.

## Staging Rules

Use the file-specific methods for normal creation:

- `writeSkillMarkdown(name, skillMd)`
- `writeInterface(name, interfaceScala)`
- `writeImplementation(name, implementationScala)`
- `writeManifest(name, universe, bindingName, interfaceFqn, implementationPackage)`

Write exactly one file per `run_scala_code` call. Do not call two write methods in the same Scala block, and do not emit multiple staged-skill-authoring tool calls in one response. The write method must be the final expression so the checklist is returned. After a write, stop and let the returned checklist determine the next call.

Each file-specific write returns a checklist with `written`, `remaining`, and `complete` fields. Use `stagedSkills.checklist(name)` to inspect progress without writing before choosing the next file. Use `write`, `read`, `list`, `makeDirectories`, and `exists` only to inspect or adjust files inside the staged workspace; raw `write` also counts as the one file write for the call.

All paths are relative to the staged workspace. Absolute paths, `..` parent segments, and symbolic links are rejected. The workspace directory is created lazily only by mutating operations.
