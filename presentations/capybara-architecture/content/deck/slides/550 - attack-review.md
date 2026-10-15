---scala
(id = "attack-review", seconds = 75, layout = "standard")
---

## A leak has several possible return paths

| Path to inspect | Concrete failure to catch |
| --- | --- |
| `read` and `diff` results | Source file or patch contains fake canary |
| `puts` / `putsErr` | Caller prints data from a privileged getter |
| Exception formatting | Error message includes a credential or request header |
| Test logs and artifacts | Project encodes data into process output |
| PR text and destination | Publisher receives unreviewed content or another repo |

Check what reaches history: `messagesFromChunks` appends `result.encodeAsJson` directly.

{{stack footer}}

Source: [history insertion](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ScalaAgent.scala#L371-L375), [error result normalization](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L406-L422)

{{end-stack}}

## Speaker notes

The public coding API and backend tests cover these release paths. Fixed results and credential-free test input are supporting boundaries, not a universal proof. Use only fake data to exercise failure paths. If a real secret reaches an output or repository, revoke/rotate it and investigate exposure; removing a file from the latest commit does not remove earlier disclosures. Threats include malicious repository content and dependency/test code, even if the model itself follows instructions.
