---scala
(id = "runtime-components", seconds = 75, layout = "standard")
---

## A filesystem request through four functions

| Step | Input and result |
| --- | --- |
| `ModelProvider.request` | Streams the selected model's response |
| `ScalaAgent.onChunk` | Resolves `universe = "filesystem"` to a loaded skill |
| `ReplExec.runCodeHarness` | Compiles and runs `puts(filesystem.pwd())` |
| `ScalaAgent.messagesFromChunks` | Encodes the result as a tool message in history |

`Skills.resolve(requestedUniverses, …)` selects skills and their manifest contexts. An unknown name returns `Universe not found`, without invoking the REPL.

{{stack footer}}

Source: [tool dispatch](assets/source/scala-agent.html), [history encoding](assets/source/scala-agent.html)

{{end-stack}}

## Speaker notes

Sources: src/ScalaAgent.scala, src/ModelProvider.scala, src/Skills.scala, src/ReplExec.scala, src/Logger.scala, src/UsageTracker.scala, src/CancellationToken.scala. Each provider normalizes text, thinking, tool-call and usage events. Provider authentication belongs to the host runtime. The safe snippet receives capability parameters instead of ambient host credentials.
