---scala
(id = "test-sandbox", seconds = 75, layout = "standard")
---

## The test operation executes in a separate restricted process

```text
command:    node test/word-count.test.mjs
input:      approved source and frozen tests in a read-only snapshot
environment:no host HOME, GitHub token or provider key
processes:  no child process creation or other executable
network:    denied
result:     TestSummary("passed" | "failed" | "timeout")
```

Every test call probes those boundaries first. Raw stdout and stderr go to the null device. Unsupported runtimes return `SANDBOX_UNAVAILABLE`.


{{stack footer}}

Source: [sandbox and probes](assets/source/workshop-backend.html), [real process-boundary tests](assets/source/workshop-tests.html)

{{end-stack}}

## Speaker notes

Node’s built-in node:test API runs the test file directly. Omitting --test avoids the coordinator spawning a child. The sandbox still permits OS metadata, root-directory enumeration and specific OS runtime reads. Limits cover V8 heap, CPU, file sizes, descriptors and wall time. Tests exercise malicious module attempts to read host data, access network and spawn a shell. No production security proof is claimed.
