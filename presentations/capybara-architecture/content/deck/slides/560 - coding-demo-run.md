---scala
(id = "coding-demo-run", seconds = 90, layout = "standard")
---

## Coding demo: ordinary skill-run with a snippet

```sh
./mill run workshop-setup
. .agent-runtime/demo-workspaces/coding.env
./mill run skill-run --skill coding \
  --file examples/snippets/coding.scala
```

Run from the Capybara checkout. The workspace begins with `Greeting.scala` and `README.md`. The snippet lists, reads, edits and creates text files.

{{stack footer}}

Source: [setup command](assets/source/workshop-setup.html), [snippet](assets/source/coding-snippet.html), [recorded CLI stdout](assets/source/coding-demo.html), [CLI runner](assets/source/skill-runner.html)

{{end-stack}}

## Speaker notes

This uses the ordinary skill-run CLI; no custom demo API is needed. CodingDemoSuite exercises the same CLI dispatch and checked-in snippet with a temporary host-configured workspace.
