---scala
(id = "tool-declaration", seconds = 75, layout = "standard")
---

## A tool definition describes callable input

```json
{
  "type": "function",
  "function": {
    "name": "run_scala_code",
    "parameters": {
      "type": "object",
      "properties": {
        "universe": {"type": "string"},
        "universes": {"type": "array", "items": {"type": "string"}},
        "scala_code": {"type": "string"}
      },
      "required": ["scala_code"]
    }
  }
}
```

Use `universes` for several skills; `universe` remains supported. This schema illustrates `ScalaToolCall`. Capybara derives its real schema and parser from that type. The schema describes arguments; runtime capabilities still control effects.

{{stack footer}}

Source: [schema derivation](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OllamaClient.scala#L72-L89), [tool argument type](assets/source/scala-agent.html), [Function calling guide](https://developers.openai.com/api/docs/guides/function-calling)

{{end-stack}}

## Speaker notes

The actual tool definition also supplies a description. This slide shows a reduced valid function definition rather than claiming exact serialized-byte equivalence to the derived schema. The adapter leaves strict unset. Independently validate model-supplied arguments before executing. A property named universe does not itself grant filesystem or process access.
