---scala
(id = "skill-build-plan", seconds = 75, layout = "standard")
---

## A skill produces two separately compiled JARs

1. Read metadata, manifest, interface source and implementation source.
2. Compare a digest with `.agent-runtime/interface/<name>/sigs.hash`.
3. Package **only the interface** as `sigs.jar` in safe mode.
4. Package the implementation as `impl.jar`, with `sigs.jar` on its compile classpath.
5. Publish the digest only after both JARs succeed.

The baseline builtins follows the same pipeline. `basic` adds no selected-skill JARs.


{{stack footer}}

Source: [ensureCompiled](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L375-L380), [compileSkill](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L440-L525)

{{end-stack}}

## Speaker notes

A failed rebuild removes the hash marker before replacing artifacts. The digest includes Scala version, interface source, implementation source, API description, predef and bindings. Skill names and universe names must agree because packaging and loading use different fields.
