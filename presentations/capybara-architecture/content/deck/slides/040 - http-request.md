---scala
(id = "http-request", seconds = 75, layout = "standard")
---

## The HTTP envelope and request body

```http
POST /v1/chat/completions HTTP/1.1
Host: <configured API host>
Content-Type: application/json
Authorization: Bearer <host-owned credential>
```

```json
{
  "model": "<configured-model>",
  "stream": true,
  "messages": [
    {"role": "user", "content": "What is 1 + 2 + 3?"}
  ]
}
```

This is a minimal wire sketch. Capybara also sends its system prompt and `tools` definition. The base URL can include a proxy/version path. With no configured key, the adapter removes the Authorization header.

{{stack footer}}

Source: [ChatBody and authentication](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L62-L86), [Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

{{end-stack}}

## Speaker notes

Angle-bracket values are explanatory placeholders, never real credentials. The minimal request demonstrates field roles and is not a complete capture of Capybara’s actual body. createChatCompletionAsInputStream enables streaming. stream_options includes usage in the actual client. A local compatible endpoint may need no key. HTTP header authentication is separate from the model-visible messages.
