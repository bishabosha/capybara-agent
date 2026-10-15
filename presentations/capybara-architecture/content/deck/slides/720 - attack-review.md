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

Source: [history insertion](assets/source/scala-agent.html), [error result normalization](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

Review every capability API that can release data. The generic file API returns ordinary text; GitHub authentication stays inside its trusted implementation. Use only fake data to exercise failure paths. If a real secret reaches an output or repository, revoke/rotate it and investigate exposure; removing a file from the latest commit does not remove earlier disclosures. Threats include malicious repository content and dependency/test code, even if the model itself follows instructions.
