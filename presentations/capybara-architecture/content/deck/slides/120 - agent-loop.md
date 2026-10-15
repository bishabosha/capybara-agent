---scala
(id = "agent-loop", seconds = 75, layout = "standard")
---

## A turn continues when the model calls a tool

```scala
val updatedHistory =
  ScalaAgent.appendCollectedChunks(history, response.chunks)
if ScalaAgent.hasToolCalls(response.chunks) then
  continueSession(updatedHistory, requestCount + 1,
    usageTracker, contextWindow, keepAlive, think, cancellationToken)
else Future.successful(Result.Ok(updatedHistory))
```

This excerpt is the branch in `continueSession`. Tool calls add another model request. A response without tool calls returns the updated history.

The guard rejects a 17th request in the same turn.

{{stack footer}}

Source: [continueSession](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/scalaAgentDemo.scala#L121-L152), [hasToolCalls](assets/source/scala-agent.html)

{{end-stack}}

## Speaker notes

Source: src/scalaAgentDemo.scala, src/ScalaAgent.scala. Explain that the agent loop chooses actions, while the compiler and skill implementations enforce their own boundaries. The model sees the tool contract and skill interfaces. It does not receive implementation source through the skill prompt. A request limit bounds model iterations, not the wall time or resource use of an individual snippet.
