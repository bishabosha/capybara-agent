---scala
(id = "capture-types", seconds = 75, layout = "standard")
---

## The function type records retained capabilities

```scala
val trim: String -> String = text => text.trim
// No capability references in the closure

val log: String ->{output} Unit = text => puts(text)
// Explicitly records the retained output handle
```

`String => Unit` permits capability captures through the general function type. An API parameter typed `A -> B` demands a callback with no retained capabilities.

These annotations track references to handles, rather than the contents of every string.

{{stack footer}}

Source: [supplied output parameter](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/builtin-skills/capybara-builtins/scripts/Manifest.scala#L5-L9), [Scala capture-set syntax](https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/basics.html)

{{end-stack}}

## Speaker notes

The first two declarations illustrate pure and explicit-capture function types. The linked repository manifest identifies the output handle that the logger retains. The shipped regression tests cover the pure logger rejection; the explicit capture-set repair is a proposed compiler exercise and has not been compiled during authoring. Pure here means no retained capabilities, not totality, absence of divergence, or mathematical purity. Capture checking is not general data taint tracking.
