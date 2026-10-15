---scala
(id = "coding-demo-call", seconds = 90, layout = "standard")
---

## Demo: the complete text-editing tool call

{{stack transcript}}

```scala
puts(coding.list("."))
puts(coding.read("Greeting.scala"))
coding.edit("Greeting.scala") { text =>
  text.replace("hello, world", "hello, capybara")
}
coding.write("notes.txt", "Edited through the coding capability.\n")
puts(coding.read("Greeting.scala"))
puts(coding.read("notes.txt"))
```

The pure callback uses only its input text. File effects and reporting happen outside it.

{{end-stack}}

{{stack footer}}

Source: [CLI runner](assets/source/skill-runner.html), [recorded output](assets/source/coding-demo.html), [snippet](assets/source/coding-snippet.html)

{{end-stack}}

## Speaker notes

The selected skill is coding. This is the actual examples/snippets/coding.scala file. The shell setup supplies a fresh workspace containing Greeting.scala and README.md.
