---scala
(id = "output", seconds = 75, layout = "standard")
---

## The same output travels through three formats

```scala
puts(6)
putsErr("review needed")
```

The builtin emits one JSON line per call:

```json
{"special.success":6}
{"special.error":"review needed"}
```

The agent decodes separate channels into `{"success":true,"stdout":[6],"stderr":["review needed"]}`. A bare final `6` emits nothing.

{{stack footer}}

Source: [JSON stream writes](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/builtin-skills/capybara-builtins/scripts/Implementation.scala#L118-L133), [decodeOutput](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L355-L373)

{{end-stack}}

## Speaker notes

Source: builtin-skills/capybara-builtins/scripts/Interface.scala and Implementation.scala, src/ReplExec.scala. Both output streams reach the conversation. stderr alone does not mark execution as failed. Stream capture here means collecting output. Scala capture checking, covered later, means tracking capability references. Exceptions, object formatting and toString are also potential data release paths.
