---scala
(id = "session-state", seconds = 75, layout = "standard")
---

## Conversation state and execution state have different lifetimes

| State | Lifetime |
| --- | --- |
| User/assistant/tool messages | Persist across interactive turns |
| Token usage and request count | Recorded per response, limit per turn |
| REPL definitions | Reset after each tool execution |
| Skill JAR cache | Reused when its source digest matches |
| Files written by skills | Remain after REPL reset |

`/x` coordinates cancellation with the request and active REPL execution. The 16-request limit does not bound one snippet's CPU, memory or I/O.


{{stack footer}}

Source: [interactive history and cancellation](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/scalaAgentDemo.scala#L24-L94), [stateless reset](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

The editor and GitHub skills execute their declared effects in the host JVM. Their safe interfaces and harness define the capability boundary; neither includes a separate process runner.
