---scala
(id = "editing-demo-output", seconds = 90, layout = "standard")
---

## Coding demo: inspect stdout and the edited files

```text
stdout[0]  ["Greeting.scala", "README.md"]
stdout[1]  val greeting = "hello, world"
stdout[2]  val greeting = "hello, capybara"
stdout[3]  Edited through the editing capability.
```

```sh
cat "$CAPYBARA_WORKSPACE_ROOT/Greeting.scala"
cat "$CAPYBARA_WORKSPACE_ROOT/notes.txt"
```

The numbered rows decode the CLI’s `special.success` JSON values. The linked recording preserves its exact structured stdout.

{{stack footer}}

Source: [recorded CLI stdout](assets/source/editing-demo.html), [snippet](assets/source/editing-snippet.html)

{{end-stack}}

## Speaker notes

This result comes from ordinary skill-run --file examples/snippets/editing.scala. Rejected snippets are demonstrated separately after both accepted demos.
