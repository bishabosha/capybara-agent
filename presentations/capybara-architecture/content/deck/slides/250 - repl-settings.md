---scala
(id = "repl-settings", seconds = 75, layout = "standard")
---

## Initial REPL settings and output routing

```scala
settings = Array(
  "-classpath", ScalaClassLoader.getURLs
    .map(_.toURI().getPath()).mkString(":"),
  "-language:experimental.captureChecking",
  "-Xrepl-interrupt-instrumentation:true",
  "-color:never"
)
```

The driver uses `Some(ScalaClassLoader)`, an empty `extraPredef`, and a quiet initial state. `redirectOutput` is false: the harness passes explicit stream handles rather than replacing global process streams.

{{stack footer}}

Source: [Session constructor and state](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L74-L96)

{{end-stack}}

## Speaker notes

These are the actual initial ReplDriver options. The classpath string contains the Scala library loader's URLs; selected APIs join later through :jar commands. Interrupt instrumentation participates in cancellation. Quiet mode suppresses interactive result printing but does not replace the explicit output capability or rollback external effects. Host compiler diagnostics have a separate capture buffer.
