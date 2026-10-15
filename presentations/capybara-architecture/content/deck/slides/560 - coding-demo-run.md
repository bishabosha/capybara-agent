---scala
(id = "coding-demo-run", seconds = 90, layout = "standard")
---

## Coding demo: ordinary skill-run with a snippet

**1. Prepare the workspaces**

```sh
./mill run workshop-setup
```

**2. Load the coding workspace**

```sh
source .agent-runtime/demo-workspaces/coding.env
```

**3. Run the snippet**

```sh
./mill run skill-run --skill coding \
  --file examples/snippets/coding.scala
```

Use the same terminal. The `\` continues one command onto the next line.

{{stack footer}}

Source: [setup command](assets/source/workshop-setup.html), [snippet](assets/source/coding-snippet.html), [recorded CLI stdout](assets/source/coding-demo.html), [CLI runner](assets/source/skill-runner.html)

{{end-stack}}

## Speaker notes

Run these three commands in order in the same Bash or Zsh terminal. source loads CAPYBARA_CODING_ROOT into that terminal, so the following skill-run inherits it. In Bash and Zsh, source file is equivalent to . file; POSIX sh uses the dot form. The backslash in step 3 is a line continuation, so those two displayed lines are one command. This remains an ordinary skill-run invocation with the checked-in snippet.
