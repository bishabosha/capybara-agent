---scala
(id = "providers", seconds = 75, layout = "standard")
---

## Providers share one chunk and tool-call contract

| `CAPYBARA_PROVIDER` | Request path |
| --- | --- |
| unset or `ollama` | `OllamaClient.request` |
| `openai` | `OpenAICompatibleClient.request` |
| `openai-responses` | `OpenAIResponsesClient.request` |

`ModelProvider.request` selects the client. `ScalaAgent` consumes the normalized stream of content, thinking, tool calls and usage.

For OpenAI-compatible providers, the host reads `CAPYBARA_API_KEY` or `OPENAI_API_KEY`. Neither file nor GitHub interface exposes the host provider keys.


{{stack footer}}

Source: [provider dispatch](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ModelProvider.scala#L18-L56), [tool schema and request](assets/source/scala-agent.html)

{{end-stack}}

## Speaker notes

No provider key belongs in a manifest, snippet, repository remote URL, test environment or transcript. The host can contact the selected model; a skill does not automatically receive that HTTP authority.
