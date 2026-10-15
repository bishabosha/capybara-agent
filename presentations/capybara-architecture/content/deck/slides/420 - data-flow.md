---scala
(id = "data-flow", seconds = 75, layout = "standard")
---

## The secrets API returns a printable string

```scala
trait Dict extends SharedCapability {
  def get(key: String): Option[String]
  def put(key: String, value: String): Unit
}
```

`Dict` is a tracked capability. But `get` deliberately releases ordinary string data to its caller.

The caller can pass that result to `puts`. Capture checking does not recover the confidentiality that the API already released.

{{stack footer}}

Source: [public Dict API](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/secrets/scripts/Interface.scala#L8-L11), [get returns stored value](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/secrets/scripts/Implementation.scala#L13-L15)

{{end-stack}}

## Speaker notes

Sources: skills/secrets/scripts/Interface.scala and Implementation.scala. This is an architectural inference from the API: Dict.get returns Option[String], so its contents can reach the output API. Capability tracking is not a general information-flow/taint system for string contents. Emphasize this distinction before discussing GitHub credentials.
