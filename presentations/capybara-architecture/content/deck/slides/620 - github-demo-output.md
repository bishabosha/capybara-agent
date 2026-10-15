---scala
(id = "github-demo-output", seconds = 90, layout = "standard")
---

## GitHub demo: recorded CLI results and remote effects

| Result | Recorded local GitHub fixture |
| --- | --- |
| Issue | #1: Fix whitespace λ |
| Local edit | Empty input and repeated whitespace handled |
| Published branch | `capybara/issue-1`; `main` unchanged |
| Draft PR | `https://github.com/example/lab/pull/2` |
| CI | `pending` for the published commit |

The recording uses synthetic credentials and the ordinary CLI. The test server checks the authorization header, uploaded contents, draft flag and non-force update.

{{stack footer}}

Source: [recorded CLI stdout](assets/source/github-demo.html), [CLI regression test](assets/source/github-tests.html)

{{end-stack}}

## Speaker notes

These are actual CLI results and assertions from GitHubSkillSuite, not a live PR created during deck authoring. A later test-server response moves exact-commit checks to passed. A real run returns your repository’s actual URLs and CI state.
