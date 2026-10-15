---scala
(id = "message-roles", seconds = 75, layout = "standard")
---

## Messages carry the conversation

| Role | Meaning in Capybara's Chat Completions adapter |
| --- | --- |
| `system` | Agent instructions and available skill interfaces |
| `user` | The user's current request or earlier turn |
| `assistant` | Model text and optional `tool_calls` |
| `tool` | Host-produced result for one `tool_call_id` |

The order matters. An assistant tool call comes before its matching tool result. A tool result is data added to context, rather than another user instruction.

{{stack footer}}

Source: [role conversion](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L20-L41), [message constructors](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OllamaClient.scala#L212-L273)

{{end-stack}}

## Speaker notes

These are the roles the current adapter implements. This is not an exhaustive statement about every role the OpenAI API supports. Message conversion deliberately handles system/user/assistant/tool and rejects unsupported roles. Tools can return untrusted repository text, so the surrounding agent policy must preserve its status as data.
