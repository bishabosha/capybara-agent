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

`availableSkills.find(_.universe == …)` performs selection. An unknown name returns `Universe not found`, without invoking the REPL.

{{stack footer}}

Source: [tool dispatch](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ScalaAgent.scala#L252-L283), [history encoding](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ScalaAgent.scala#L355-L390)

{{end-stack}}

## Speaker notes

Sources: src/ScalaAgent.scala, src/ModelProvider.scala, src/Skills.scala, src/ReplExec.scala, src/Logger.scala, src/UsageTracker.scala, src/CancellationToken.scala. Each provider normalizes text, thinking, tool-call and usage events. Provider authentication belongs to the host runtime. Do not expose its environment to the isolated test process.
