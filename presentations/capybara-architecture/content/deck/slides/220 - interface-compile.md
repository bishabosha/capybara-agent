---scala
(id = "interface-compile", seconds = 75, layout = "standard")
---

## Interface packaging: safe mode and its own dependencies

For `filesystem`, `compileSkill` stages `Interface.scala` under `.agent-runtime/temp/filesystem/` and invokes:

```sh
scala --power package -S 3.10.0 --library -f \
  -o .agent-runtime/temp/filesystem/sigs.jar \
  -language:experimental.safe \
  .agent-runtime/temp/filesystem/Interface.scala
```

Interface `//> using` directives remain in that file. No implementation source joins this compilation. The trusted factory cannot make an unsafe public interface acceptable.


{{stack footer}}

Source: [interface package command](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L468-L489)

{{end-stack}}

## Speaker notes

The command is shown with shell line wrapping and repository-relative paths for readability. The actual subprocess receives path arguments as separate values. Version comes from ReplExec.ScalaVersion, matching the agent compiler. All compilation in this work is initiated through MCP tests.
