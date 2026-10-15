---scala
(id = "tool-call-message", seconds = 75, layout = "standard")
---

## The assistant requests a tool call

```json
{
  "role": "assistant",
  "tool_calls": [{
    "id": "call_1",
    "type": "function",
    "function": {
      "name": "run_scala_code",
      "arguments": "{\"universe\":\"basic\",\"scala_code\":\"puts(6)\"}"
    }
  }]
}
```

`arguments` is a JSON-encoded string on this wire protocol. The host parses it into `ScalaToolCall`, then executes the requested universe/body pair.

`call_1` identifies this invocation. It is different from the function name and the REPL method's per-call UUID.

{{stack footer}}

Source: [wire call conversion](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L26-L40), [argument parsing after stream](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L135-L147)

{{end-stack}}

## Speaker notes

This is an illustrative assistant message, with a normalized tool-call ID. The host can receive several calls using the same function name, each with its own ID. The string field must be decoded as JSON before the Scala body can be checked. The single run_scala_code function is the bridge to any selected skill universe.
