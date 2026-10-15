---scala
(id = "github-demo-run", seconds = 90, layout = "standard")
---

## GitHub demo: ordinary skill-run against your lab repository

```sh
. .agent-runtime/demo-workspaces/github.env
export CAPYBARA_GITHUB_REPOSITORY=YOUR-ACCOUNT/YOUR-LAB
export CAPYBARA_GITHUB_BASE=main
# Supply CAPYBARA_GITHUB_TOKEN through your secret manager.
./mill run skill-run --skill github \
  --file examples/snippets/github-lab.scala
```

Run `workshop-setup` once, then use its prepared workspace and a lab repository with issue #1. `github`’s manifest adds `coding`. Choose a fresh topic branch for each run. The snippet performs real branch and PR writes.

{{stack footer}}

Source: [setup guide](assets/source/github-guide.html), [full snippet](assets/source/github-snippet.html), [recorded CLI stdout](assets/source/github-demo.html)

{{end-stack}}

## Speaker notes

Use the setup guide for the hidden token prompt and repository permissions. The recorded demonstration uses a local GitHub HTTP fixture and synthetic credentials, through the same skill-run CLI. The production command uses the real GitHub implementation and configured origin.
