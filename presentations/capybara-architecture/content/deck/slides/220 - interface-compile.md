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

Source: [interface package command](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

The command is shown with shell line wrapping and repository-relative paths for readability. The actual subprocess receives path arguments as separate values. Version comes from ReplExec.ScalaVersion, matching the agent compiler. All compilation in this work is initiated through MCP tests.
