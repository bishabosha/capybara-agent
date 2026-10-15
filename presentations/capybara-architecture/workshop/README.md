# Text editing and capture checking

The `coding` universe is a general text-file capability, independent of language
and repository layout. The checked `Interface.scala` and agent harness determine
which effects are available. The implementation uses JDK file operations
inside the host JVM; it launches no process or OS sandbox.

## Participant setup

Clone the Capybara repository and open a terminal at its root. Install a JDK and
Scala CLI (`scala` on PATH); use the checked-in `./mill` launcher. Then run:

```sh
./mill run workshop-setup
```

This command creates both demo workspaces from tracked starters, including the
GitHub lab's hidden CI files, and builds the safe skill APIs and implementations.
It needs no model account or GitHub credential. Repeating it preserves edits.
It prints each actual workspace path and the ordinary commands to run next.

The generated `coding.env` and `github.env` files select the local file root;
they contain no tokens. Source one to switch demos. To start a fresh session
without losing work, use `--directory .agent-runtime/another-workshop` and the
paths printed by that invocation.

## Coding demo

From the same checkout:

```sh
. .agent-runtime/demo-workspaces/coding.env
./mill run skill-run --skill coding --file examples/snippets/coding.scala
```

This ordinary CLI command lists files, reads and edits `Greeting.scala`, creates
`notes.txt` and prints structured results. The demo slides show the full snippet
and stdout recorded from this same command path. `CodingDemoSuite` configures only
the trusted root factory for its temporary fixture; it changes no public API.

## Develop and use the skill

```sh
export CAPYBARA_CODING_ROOT=/absolute/path/to/project
./mill run skill-ide --skill skills/coding
./mill run skill-run --skill skills/coding --file /path/to/snippet.scala
```

Open the generated workspace in Metals or IntelliJ, edit the original interface
and implementation, then run a snippet through the real harness. The host picks
the root. Within it the agent can list, read, create and edit ordinary text files.
The default is the process's current directory. New files require an existing
parent directory. The generic implementation checks paths, rejects symlinks,
limits files to 1 MiB and performs expected-content replacement.

```scala
puts(coding.list("."))
val before = coding.read("README.md")
coding.edit("README.md") { text =>
  text.replace("Old heading", "New heading")
}
coding.write("notes.txt", "Update the examples.\n")
puts(coding.read("README.md"))
```

These are ordinary text operations; the skill does not embed a test framework,
a language, credential scanner or hardcoded list of project files.

## Exercise: reject captured effects

Run each independently:

```scala
coding.edit("README.md") { text =>
  puts(text)
  text
}
```

```scala
coding.edit("README.md")(text => coding.read("other.txt"))
```

Both must fail compilation before the file changes. The first retains output;
the second retains the file capability. A transformation using only its input
string is accepted. Report with `puts` after the edit, outside the callback.

## Exercise: distinguish authority from contents

```scala
coding.write("example.txt", "LAB_ONLY_CANARY_EXAMPLE")
puts(coding.read("example.txt"))
```

This is allowed: the file API intentionally releases ordinary printable text.
Capture checking tracks capability references, not confidential string contents.
Explain why a general file-reading grant must not include credential files.
Then compare the separate GitHub API, which authenticates inside trusted code
and has no token getter.

## Exercise: compiler rejection or implementation error

- Capturing `puts` or `coding` in an `edit` callback: compiler rejection.
- Calling `editing.internal.impl()` from a snippet: `rejectSafe` rejection.
- Direct process, ambient file or environment access: safe-mode rejection in the
  tested cases.
- Reading `../credentials`: implementation returns `PATH_DENIED`.
- Replacing with stale complete contents: implementation returns `CONTENT_CHANGED`.
- Writing an ordinary workspace file: allowed by the supplied file capability.

## GitHub issue-to-PR workflow

Setup creates `.agent-runtime/demo-workspaces/github` in each participant's
checkout. It copies the pristine starter, ready for the snippet to edit
`src/word-count.mjs`. Select it with:

```sh
. .agent-runtime/demo-workspaces/github.env
```

The earlier absolute path in the slide is an example of this checkout's location;
participants use their own generated environment file. Configure the remote
GitHub repository and host credential separately.

The `github` universe separately supplies issue lookup, branches, commit/push
and draft PR creation. Text edits belong to `coding`. Its host owns the token and repository selection. Follow `skills/github/README.md` to configure the host environment and
publish the pristine starter under `workshop/project` to your own lab repository.
Use only synthetic data. The example expects issue #1. Its public commit method
requires a `TextFiles` context; manifest `requires` supplies coding automatically.

```sh
./mill run skill-run --skill github \
  --file examples/snippets/github-lab.scala
```

This performs real authenticated branch/PR writes for the configured repository.
Use the same pure coding.edit callback while both skills are selected. Try
capturing output and the GitHub capability to observe the compile-time restriction.
Neither skill runs a process or OS sandbox. Functional checks can run independently.
Review the final diff and report the exact published commit's CI status.

Run the checked-in counterexamples separately:

```sh
./mill run skill-run --skill coding --file examples/snippets/coding-capture-output.scala
./mill run skill-run --skill github --file examples/snippets/github-capture-in-edit.scala
./mill run skill-run --skill github --file examples/snippets/github-hidden-files.scala
```

Each must fail compilation. The last one tries to hide a `TextFiles` capture in
a function typed `GitHub -> Unit`; public contextual requirements expose it.

## Validation

Run `WorkshopSuite`, `CodingDemoSuite` and `GitHubSkillSuite` through Metals MCP.
The first two validate generic text editing and capture checking. The GitHub
suite uses a local HTTP fixture; it makes no authenticated live push
or PR. SkillCompositionSuite verifies local publication of safe API artifacts and
dependency-aware capability injection.
