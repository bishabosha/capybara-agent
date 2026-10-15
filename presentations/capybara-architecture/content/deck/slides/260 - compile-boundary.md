---scala
(id = "compile-boundary", seconds = 75, layout = "standard")
---

## The REPL explicitly switches compiler modes

```scala
val language =
  if enabled then "experimental.safe"
  else "experimental.captureChecking"
val _ = modeContext.setSetting(modeContext.settings.language, Nil)
runWithState(s":settings -language:$language")
```

`setSafeMode` clears previous language features because `:settings` appends them. Safe mode checks the snippet first. Trusted mode then compiles its caller.

Implementation JAR packaging omits the snippet's safe-mode flag.

{{stack footer}}

Source: [setSafeMode](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L174-L181), [separate implementation packaging](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L490-L517)

{{end-stack}}

## Speaker notes

Source: src/ReplExec.scala. The REPL explicitly clears its language setting when switching modes because :settings appends features and cannot turn safe mode off by merely adding a flag. The implementation may opt into capture checking itself, but packaging does not apply the snippet's safe-mode restrictions. Do not allow a user-controlled binding value or predef to enter trusted wiring.
