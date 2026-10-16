---scala
(id = "editing-demo-call", seconds = 90, layout = "standard")
---

## Demo: the complete text-editing tool call

{{stack transcript}}

```scala
puts(filesystem.list("."))
puts(filesystem.read("Greeting.scala"))
editing.edit("Greeting.scala") { text =>
  text.replace("hello, world", "hello, capybara")
}
filesystem.write("notes.txt", "Edited through the editing capability.\n")
puts(filesystem.read("Greeting.scala"))
puts(filesystem.read("notes.txt"))
```

The pure callback uses only its input text. File effects and reporting happen outside it.

{{end-stack}}

{{stack footer}}

Source: [CLI runner](assets/source/skill-runner.html), [recorded output](assets/source/editing-demo.html), [snippet](assets/source/editing-snippet.html)

{{end-stack}}

## Speaker notes

The selected skill is editing. This is the actual examples/snippets/editing.scala file. The shell setup supplies a fresh workspace containing Greeting.scala and README.md.
