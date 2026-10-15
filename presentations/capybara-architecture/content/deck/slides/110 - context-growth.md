---scala
(id = "context-growth", seconds = 75, layout = "standard")
---

## The next HTTP request carries the expanded context

| Request | Ordered `messages` sent by the host |
| --- | --- |
| Initial question | system, user |
| After tool execution | system, user, assistant with `call_1`, tool result for `call_1` |
| A later user turn | system, earlier user/call/result/final answer, new user |

The Chat Completions adapter serializes the supplied history on each request. REPL reset leaves that history intact.

This loop performs no automatic history compaction. `CAPYBARA_CONTEXT_WINDOW` informs the local usage display; it does not trim the message list in this adapter.

{{stack footer}}

Source: [serialized messages](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L66-L71), [continueSession](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/scalaAgentDemo.scala#L121-L152), [context display setting](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ScalaAgent.scala#L37-L42)

{{end-stack}}

## Speaker notes

The table explains the current implementation, not all OpenAI conversation-state APIs. Chat Completions is distinct from the Responses adapter, which builds input items and preserves encrypted reasoning when provided. Internal thinking fields are not blindly copied as Chat Completions message roles. Long source/results enlarge future requests, so release limits and bounded tool summaries also control context growth. HTTP keep-alive does not replace application-managed history.
