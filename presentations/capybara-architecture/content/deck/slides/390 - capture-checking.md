---scala
(id = "capture-checking", seconds = 75, layout = "standard")
---

## Why this logger callback fails

```scala
val log: String -> Unit = message => puts(message)
```

`String -> Unit` promises an empty capture set. The callback calls this helper:

```scala
final def puts(value: into[Showable])(using OutputStreams): Unit =
  summon[OutputStreams].print(value)
```

The implicit argument is the snippet's `output` handle. Retaining it violates the declared empty capture set. The linked regression expects a compilation error.

{{stack footer}}

Source: [pure callback rejection](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/test/src/ReplExecSuite.scala#L147-L150), [helper implementation](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/builtin-skills/capybara-builtins/scripts/Interface.scala#L30-L31)

{{end-stack}}

## Speaker notes

Sources: test/src/ReplExecSuite.scala test "a pure callback cannot forget the output capability it captures", builtins Interface.scala. Scala reference: https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/basics.html . The -> syntax denotes a function with an empty capture set. Here pure means no retained capabilities. It does not guarantee mathematical purity or prevent divergence. The failed example is an existing regression test, not a claim based on an unexecuted new sample.
