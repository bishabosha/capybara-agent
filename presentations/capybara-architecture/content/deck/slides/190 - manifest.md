---scala
(id = "manifest", seconds = 75, layout = "standard")
---

## A real manifest binding

```scala
val Manifest = (
  universe = "filesystem",
  api = "The harness supplies filesystem: fs.Filesystem as a capability parameter.",
  predef = "",
  bindings = Vector((name = "filesystem", tpe = "fs.Filesystem",
    value = "fs.internal.impl()"))
)
```

`name` becomes the snippet's parameter. `tpe` becomes its type. `value` executes in trusted wiring to construct the argument.

{{stack footer}}

Source: [filesystem Manifest.scala](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/filesystem/scripts/Manifest.scala#L1-L6), [factory](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/filesystem/scripts/Implementation.scala#L24-L25)

{{end-stack}}

## Speaker notes

The excerpt preserves the filesystem manifest values and reformats the binding for slide width. Skills.parseManifest reads its named tuple. SkillScaffold.definition uses name and tpe in the safe method signature; invocation evaluates value in trusted code. Keep name and universe equal because packaging uses skill.name while JAR loading uses skill.universe. Bindings come from trusted skill authors, never the model.
