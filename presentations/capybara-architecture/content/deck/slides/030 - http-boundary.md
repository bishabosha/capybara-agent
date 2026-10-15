---scala
(id = "http-boundary", seconds = 75, layout = "standard")
---

## An agent makes HTTP requests and executes tools locally

| Participant | Responsibility |
| --- | --- |
| Capybara host | Builds the request body and authenticates to the configured endpoint |
| Model provider | Receives messages and tool definitions, streams generated text or tool calls |
| Capybara executor | Resolves the selected skill, checks Scala and executes the tool call |

A tool call arrives as structured response data. The host decides how to validate and execute it, then sends the result in a later model request.

This primer follows `CAPYBARA_PROVIDER=openai`, the Chat Completions adapter.

{{stack footer}}

Source: [request and stream adapter](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/OpenAICompatibleClient.scala#L53-L75), [local dispatch](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ScalaAgent.scala#L267-L283)

{{end-stack}}

## Speaker notes

The provider does not execute run_scala_code. That function name identifies a host-side dispatch path. HTTP and JSON carry requests/results. Skill APIs, safe compilation and trusted implementations determine actual authority. Ollama and Responses use different wire envelopes while feeding the same internal chunk abstraction.
