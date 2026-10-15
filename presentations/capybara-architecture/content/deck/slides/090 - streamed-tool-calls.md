---scala
(id = "streamed-tool-calls", seconds = 75, layout = "standard")
---

## Streaming delivers fragments before complete calls

```text
HTTP response: text/event-stream
data: {"choices":[{"delta":{"content":"..."}}]}
data: {"choices":[{"delta":{"tool_calls":[...]}}]}
data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}
data: [DONE]
```

This is a schematic stream. Tool names, IDs and argument strings may span events. The client accumulates argument fragments by tool-call index.

On `[DONE]`, it checks the finish reason and completeness, parses the collected JSON arguments, then emits `Chunk.Tools` for execution.

{{stack footer}}

Source: [SSE parsing](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L87-L106), [StreamDecoder](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L122-L178), [Streaming event reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)

{{end-stack}}

## Speaker notes

The ellipses intentionally denote a schematic event sequence, not executable JSON. The slide labels that distinction. Delta events do not independently authorize execution. The actual decoder tracks call indexes in a TreeMap, handles stop/tool_calls finish reasons, rejects incomplete calls and only pushes tools after the completed stream. usage can arrive separately. Responses API completion events differ from Chat Completions [DONE].
