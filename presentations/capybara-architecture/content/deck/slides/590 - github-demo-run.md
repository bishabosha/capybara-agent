---scala
(id = "github-demo-run", seconds = 90, layout = "standard")
---

## GitHub demo: ordinary skill-run against your lab repository

**1. Load the GitHub workspace**

```sh
source .agent-runtime/demo-workspaces/github.env
```

**2. Configure your lab repository (issue #1)**

```sh
export CAPYBARA_GITHUB_REPOSITORY=bishabosha/capybara-agent-github-sandbox
export CAPYBARA_GITHUB_BASE=main
```

Supply `CAPYBARA_GITHUB_TOKEN` through your secret manager.

**3. Run the snippet in the same terminal**

```sh
./mill run skill-run --skill github \
  --file examples/snippets/github-lab.scala
```


{{stack footer}}

Source: [setup guide](assets/source/github-guide.html), [full snippet](assets/source/github-snippet.html), [recorded CLI stdout](assets/source/github-demo.html)

{{end-stack}}

## Speaker notes

Use a lab repository with issue #1 and a fresh topic branch. Use the setup guide for the hidden token prompt and repository permissions. The recorded demonstration uses a local GitHub HTTP fixture and synthetic credentials, through the same skill-run CLI. The production command uses the real GitHub implementation and configured origin.
