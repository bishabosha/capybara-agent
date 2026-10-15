---scala
(id = "exercise-3", seconds = 360, layout = "dark-slide")
---

## Exercise 1: classify three compiler failures

**6 minutes.** Read the linked tests. Treat each line below as an independent snippet and predict its result.

```scala
val log: String -> Unit = message => puts(message)
val value: Any = 1; value.asInstanceOf[String]
builtins.outputStreams(null, null)
```

For the logger, try `->{output}` and explain what the annotation admits. For the other failures, identify why admitting captures does not make them valid.

**Pass condition:** connect each failure to a capture-set mismatch, an unchecked cast, or a `rejectSafe` factory.

{{stack footer}}

Source: [failure cases](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/test/src/ReplExecSuite.scala#L134-L150), [safe compilation step](assets/source/repl-exec.html)

{{end-stack}}

## Speaker notes

Answer: the logger tries to conform to an empty capture set while retaining output. An explicit output capture permits the retained handle, subject to the compiler's other checks. asInstanceOf is an unchecked cast, restricted independently of which capabilities the function captures. outputStreams is rejectSafe and remains unavailable to safe snippets. The repository tests assert Result.Err; only factory tests assert the diagnostic contains rejectSafe. Do not invent exact diagnostics for other tests. Compile experiments through MCP if connected.
