---scala
(id = "context-construction", seconds = 75, layout = "standard")
---

## Capybara builds the context in three steps

```scala
// runSession: add the current request
history :+ OllamaClient.ChatMessage.user(query)

// singleRequest: prepend the system prompt for this request
OllamaClient.ChatMessage.system(SystemPrompt) +: history

// appendCollectedChunks: retain the model response and tool results
history ++ messagesFromChunks(chunks)
```

`SystemPrompt` contains the skill descriptions, public interfaces and calling conventions. The separate request `tools` field supplies the function schema.

{{stack footer}}

Source: [new user turn](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/scalaAgentDemo.scala#L99-L119), [system prompt placement](assets/source/scala-agent.html), [history append](assets/source/scala-agent.html)

{{end-stack}}

## Speaker notes

These are excerpts from three different functions, not one executable block. The stored history does not need a duplicated system message on every iteration: singleRequest prepends the current system prompt during request assembly. Skills.renderPrompt exposes names/types, descriptions and public interfaces but not implementation source or binding factory expressions. The schemas and messages both contribute model input.
