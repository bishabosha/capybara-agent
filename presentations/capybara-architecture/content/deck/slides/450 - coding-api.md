---scala
(id = "coding-api", seconds = 75, layout = "standard")
---

## The implemented coding capability

```scala
trait Coding extends caps.SharedCapability {
  def read(path: String): String
  def replace(path: String, expected: String, next: String): Unit
  def transformSource(f: String -> String): Unit
  def test(): TestSummary
  def diff(): String
  def sandboxChecks(): Seq[String]
}
```

`coding` is a real universe. Its manifest binds `coding: workshop.Coding` through `workshop.internal.impl()`. `TestSummary` contains only a fixed status string.


{{stack footer}}

Source: [interface](assets/source/coding-interface.html), [manifest](assets/source/coding-manifest.html), [implementation](assets/source/coding-implementation.html)

{{end-stack}}

## Speaker notes

The typed pure callback is the capture-checking exercise boundary. Calls to read and diff outside that callback intentionally release approved source. No GitHub authentication, process invocation or backend configuration is a model-facing parameter.
