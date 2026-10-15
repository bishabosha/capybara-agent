# Atomic GitHub operations, composed with text editing

The `github` skill implements issue lookup, topic branches, commit/push, draft PRs
and exact-commit CI status. It uses Scala and the JDK HTTP client directly. It has
no Python helper, process sandbox, local test runner or duplicate editing API.
Local editing belongs to `coding`.

Its public interface imports the locally published safe editing API:

```scala
//> using repository ivy2local
//> using dep "dev.capybara.skills::coding-api:0.1.0-SNAPSHOT"
```

The directive supplies types for compilation. The manifest separately declares
`requires = Vector("coding")`, so selecting GitHub also supplies a coding context.
The public method declares the authority it needs:

```scala
def commit(branch: Branch, message: String, paths: Seq[String])
    (using files: editing.TextFiles): Branch
```

The generated safe method has `coding: editing.TextFiles` as a contextual
parameter, so `github.commit(...)` resolves it at the call site. GitHub's factory
and object hold no file capability. Issue lookup, branches, PRs and checks need
only GitHub authority. Without a `TextFiles` context, `commit` fails compilation;
importing its API artifact grants no file access.

## Configure and run

For the workshop, run setup from the Capybara checkout and select its prepared
workspace. Set the remote repository to your own lab repository:

Run each step separately in the same Bash or Zsh terminal.

**1. Prepare the workshop**

```sh
./mill run workshop-setup
```

**2. Load the GitHub workspace settings**

```sh
source .agent-runtime/demo-workspaces/github.env
```

**3. Configure the remote repository**

```sh
export CAPYBARA_GITHUB_REPOSITORY=YOUR-ACCOUNT/YOUR-REPOSITORY
export CAPYBARA_GITHUB_BASE=main
```

Provide `CAPYBARA_GITHUB_TOKEN` through your secret manager or a hidden prompt.
For zsh:

```sh
read -rs 'CAPYBARA_GITHUB_TOKEN?GitHub token: '
export CAPYBARA_GITHUB_TOKEN
```

Use a fine-grained or GitHub App token scoped to the repository. Grant Contents
write, Issues read, Pull requests write, and optionally Checks/Commit statuses
read for `checks`. Tokens never belong in snippets, manifests, files, PR bodies
or model messages. No method returns a token.

The local clone supplies text; the API publishes only the named files. It does
not run `git`, hooks, shell commands or tests. Keep real credentials outside the
coding workspace, because a generic read grant intentionally releases ordinary
file contents. The GitHub client additionally rejects the actual token, several
direct encodings, common token patterns and the synthetic lab canary in outgoing
files/metadata and issue text. Those checks are not universal string tainting.

## One composed tool call

```json
{
  "universes": ["coding", "github"],
  "scala_code": "..."
}
```

The legacy `universe` field still supports single-skill calls. For the developer
CLI, use comma-separated skill directories or names:

```sh
./mill run skill-run --skill coding,github \
  --file examples/snippets/github-lab.scala
./mill run skill-transcript --skill coding,github \
  --file examples/snippets/github-lab.scala
./mill run skill-ide --skill skills/github
```

Publish local safe APIs directly with `./mill run skill-publish --skill github`.
It publishes coding first, then GitHub, and prints their dependency directives.
Implementations remain separate installed skills; no factory runs during publication.

IDE preparation publishes required APIs first. The preview shows all injected
capabilities; runtime snippet bodies still compile separately in safe mode.
`skill-transcript` only renders commands and does not execute them.

The example assumes a clone of the word-count starter and issue #1. It creates
`capybara/issue-1`, edits local source through `coding`, publishes that one file
and opens a draft PR. Choose a fresh topic name for another run. With production
wiring it performs real remote writes. Automated tests use a local GitHub HTTP
fixture and synthetic credentials.

```scala
puts(github.lookupIssue(1))
val topic = github.createBranch("capybara/issue-1")
coding.edit("src/word-count.mjs") { text =>
  text.replace("return text.split(' ').length;",
    """const clean = text.trim();
  return clean === '' ? 0 : clean.split(/\s+/u).length;""")
}
val pushed = github.commit(topic, "Fix whitespace", Seq("src/word-count.mjs"))
puts(github.createPullRequest(pushed, "Fix whitespace", "Fixes #1"))
puts(github.checks(pushed))
```

For an LLM run, set the same environment and ask the agent to use coding and
GitHub together. It can list issues, inspect requirements, edit source, publish a
topic branch and report the draft PR/CI result. Functional verification can be a
separate task or skill; it is not embedded in the text or GitHub interfaces.

## Capability boundary

The generated safe method receives separate `coding`, `github` and `output`
parameters. A pure edit callback cannot retain any of them:

```scala
coding.edit("README.md")(text => { puts(text); text })
coding.edit("README.md")(text => github.lookupIssue(1).body)
```

Both fail capture checking. Calling GitHub outside that callback is authorized
by the supplied GitHub capability. Safe mode rejects direct environment, process
and reflective field access in the regression cases. Trusted factories are
marked `@caps.rejectSafe`.

`createBranch` accepts only a `capybara/` topic name and never overwrites an
existing branch. If a rejected create is followed by a read showing that the
existing topic still has exactly the base branch's head, it returns that topic.
This lets a demo recover after stopping between branch creation and the edit.
An existing topic with a different head returns `BRANCH_ALREADY_EXISTS`; choose
a new name or explicitly continue with `branch(name)`.

`commit` checks the exact expected head, uploads selected local
text and advances the ref without force. A stale `Branch` returns
`BRANCH_CHANGED`; inspect it with `branch(name)` before deciding what to do.
PR creation checks the exact head, creates a draft and reuses an existing exact
head/base PR. It cannot merge or promote it. The client rejects redirects,
disables proxies and uses the configured GitHub repository at a fixed origin.
HTTP error bodies and original exceptions never enter tool output.

API importing supplies types, not credential values. The installed skill graph
and manifest control which runtime capabilities are constructed. Pure callbacks
control retained references, not the confidentiality of arbitrary text. Existing
remote CI remains repository-controlled and runs independently of this harness.

Run `GitHubSkillSuite` and `SkillCompositionSuite` through Metals MCP. They test
real HTTP serialization, local API publication, safe API delegation, independent
editing/GitHub operations, cross-skill capture failures and credential rejection.
No live GitHub repository is mutated by these tests.

## Troubleshooting the demo

If issue #1 prints but the edited source does not, the failure is during branch
creation, before any local edit or commit. The snippet uses `capybara/issue-1`.
It now reuses that branch only if it is still at the current base head. It never
resets or force-updates an existing branch.

HTTP failures include the operation and status, for example
`READ_BASE_BRANCH: CONFLICT (HTTP 409)` or
`CREATE_PULL_REQUEST: VALIDATION_FAILED (HTTP 422)`. Response bodies, authorization
headers and original transport exceptions remain private.

After a completed demo, choose a fresh topic name for a separate run. To continue
that same branch deliberately, replace `createBranch(name)` with `branch(name)`
in your snippet. An empty remote repository first needs an initial base commit.
