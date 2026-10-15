---scala
(id = "dev-ide-boundary", seconds = 75, layout = "standard")
---

## The IDE workspace and runtime perform different checks

| Development workspace | Runtime execution |
| --- | --- |
| Original interface, implementation and manifest together | Interface packaged separately in safe mode |
| Generated preview exposes capability wiring | Model body compiles separately in safe mode |
| Dependency directives form the IDE classpath | Explicit JAR loads form the REPL classpath |
| Preview code is disposable | External effects survive call boundaries |

A green IDE compilation is useful, but the next check is a snippet through `ReplExec`.


{{stack footer}}

Source: [IDE inputs](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/SkillIde.scala#L28-L43), [runtime safe check](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L281-L293)

{{end-stack}}

## Speaker notes

The IDE does not apply the same separate safe-mode boundary as the runtime compiler. It must see trusted implementation definitions for navigation and development. Do not infer factory accessibility in safe snippets from a successful preview compilation.
