# Runnable Capybara coding workshop

The `coding` universe is implemented in `skills/coding`. It works through Capybara's existing `run_scala_code` tool, safe compiler, manifest bindings and output capability. It reads and edits a dedicated word-count repository and executes its tests in a macOS sandbox.

Requirements: macOS with `/usr/bin/sandbox-exec`, Python 3, Node 20+ as a native executable, and the normal Scala 3.10.0 Capybara runtime. Node runs the JavaScript exercise project, not Capybara. The backend uses only Python's standard library. The Scala skill uses only the JDK and Scala library because the REPL does not inherit the host's dependency classpath. There is no Docker dependency or unprotected test fallback. Unsupported runtimes return `SANDBOX_UNAVAILABLE`.

## 1. Initialize a clean lab

Run from the Capybara repository root, before starting the agent:

```sh
python3 scripts/workshop.py init --repo /tmp/capybara-word-count-lab
export CAPYBARA_WORKSHOP_ROOT=/tmp/capybara-word-count-lab
export CAPYBARA_WORKSHOP_NODE="$(command -v node)"
python3 scripts/workshop.py check --repo "$CAPYBARA_WORKSHOP_ROOT" \
  --node "$CAPYBARA_WORKSHOP_NODE"
```

`init` refuses a nonempty destination and copies only the reviewed starter files. It stores the baseline and root identity under Capybara's ignored `.agent-runtime/workshop/`, outside the coding workspace. `check` must return five fixed names: environment-cleared, outside-read-denied, outside-write-denied, network-denied and other-executable-denied. It probes a real listening local server and an outside synthetic sentinel. No real credential is needed.

For a **pristine clone of the exact starter**, use `attach` instead of `init`:

```sh
python3 scripts/workshop.py attach --repo /path/to/pristine-clone
```

Attach accepts the starter's exact files and contents, plus a real `.git` directory. It rejects additional files, changed templates and symlinks. A fixed source file is not attachable as a new baseline. Use a fresh lab directory for another attempt.

## 2. Run the complete workflow without an LLM

Use the normal Capybara CLI entry point, after building/importing the project through MCP/Metals:

```sh
./mill run skill-run --skill skills/coding --file examples/snippets/coding.scala
```

The supplied snippet checks the sandbox, reports the initial `TestSummary("failed")`, transforms the source using a pure callback, reports `TestSummary("passed")`, and prints the one-file diff. This is the actual skill and harness path used by an agent tool call. External file changes persist across REPL resets.

The baseline has three test cases: one passes and two fail. The fix passes all three. The tool returns only the fixed status `passed`, `failed`, or `timeout`, rather than trusting or forwarding raw test output. Functional test success is still an ordinary test result, not a proof that arbitrary hostile code is correct.

## 3. Run with Capybara's agent loop

Restart Capybara after adding the skill so startup discovery includes `coding`. Keep the workshop environment variables set. Configure your usual model provider, then run:

```sh
./mill run --query 'Use only the coding universe. Read README.md and test/word-count.test.mjs. Check sandboxChecks and run the baseline tests. Fix src/word-count.mjs with coding.transformSource using a pure callback. Handle empty input, repeated spaces, tabs and newlines. Run tests again and show the diff. Keep puts outside the callback. Do not publish or access credentials.'
```

Use a fresh initialized lab if the deterministic snippet already applied the fix. The default Ollama provider needs a running local server and model. Other providers use the existing `CAPYBARA_PROVIDER` configuration. Provider authentication remains in the host runtime and is absent from the helper's test process. A live model request and an authenticated GitHub push are not required to validate the deterministic workflow.

## 4. Capture-checking exercises

Run each snippet independently with universe `coding` through the agent or `skill-run`.

This callback tries to retain the output capability and must fail compilation:

```scala
coding.transformSource { source =>
  puts(source)
  source
}
```

This one tries to retain the coding capability and must also fail:

```scala
coding.transformSource(source => coding.read("README.md"))
```

The interface's `String -> String` parameter demands an empty capture set. A transformation that only uses its string argument is accepted:

```scala
coding.transformSource { source =>
  source.replace("return text.split(' ').length;",
    """const clean = text.trim();
  return clean === '' ? 0 : clean.split(/\s+/u).length;""")
}
puts(coding.test())
puts(coding.diff())
```

The callback restriction controls retained capabilities. It does not turn ordinary strings into confidential data. The separate `read` and `diff` operations intentionally release approved public source. Never use the teaching `secrets` universe with real credentials.

## 5. Exercise the implemented policies

These failures happen in the trusted backend after safe compilation:

```scala
coding.read("../credentials")
// PATH_DENIED
```

```scala
coding.replace(".github/workflows/ci.yml", "", "")
// WRITE_DENIED
```

```scala
coding.replace("src/word-count.mjs", "stale content", "replacement")
// CONTENT_CHANGED
```

The backend allows only three reads: `README.md`, `src/word-count.mjs` and `test/word-count.test.mjs`. It allows only the source file to change. README and tests must match their frozen baseline. File operations pin directory descriptors and reject symlinks, hard links, nonregular files, oversized data and path escapes. Replacements use expected-content checks, serialization of RPC calls and atomic file replacement. Common token patterns, PEM private-key headers and `LAB_ONLY_CANARY_` values are refused before release or write. This pattern check is a supporting guard, not a detector for every possible secret or encoding.

Only approved source and frozen test files enter a separate read-only snapshot. `.env`, `.git`, credentials and extra files never enter it. The fixed command is **`node test/word-count.test.mjs`**: the `node:test` API runs directly without the `--test` coordinator spawning another process. The sandbox denies process creation, other executables, network access and host file contents, while allowing necessary OS runtime reads. It clears the environment, closes inherited descriptors, discards both raw output streams, limits V8 heap/CPU/files/descriptors/wall time, and reaps the process group. It permits OS metadata and root-directory enumeration needed for startup, rather than claiming an invisible host filesystem.

Every test run performs the boundary probes first. The trusted helper maps process outcomes to a fixed result schema and never returns a process-selected error message or exception text. The Scala adapter also maps failures to a fixed code list.

Validation runs through Metals MCP: `capybara.agent.WorkshopSuite`. It includes 15 backend policy/sandbox tests and public safe-harness integration tests. The latter cover the complete fix, fixed policy errors, rejected factories, callback capture failures and the generated transcript. No real credentials enter the tests.

## 6. Use the project on GitHub

The agent can edit and test this lab repository. An independent human publisher performs authenticated Git operations after reviewing the exact patch. The skill has no token getter, shell command or publish operation.

In a trusted terminal, create the baseline before asking the agent to edit:

```sh
cd "$CAPYBARA_WORKSHOP_ROOT"
git init -b main
git add README.md package.json .gitignore .github/workflows/ci.yml src test
git diff --cached
git commit -m "Add word count workshop starter"
```

Create an empty public synthetic-data repository under your own GitHub account. Add its HTTPS or SSH remote without an embedded token and publish the starter:

```sh
git remote add origin https://github.com/YOUR-ACCOUNT/capybara-word-count-lab.git
git push -u origin main
git switch -c workshop/word-count
```

Return to the Capybara checkout and run the agent from step 3. Then review and publish in the trusted terminal:

```sh
cd "$CAPYBARA_WORKSHOP_ROOT"
git add src/word-count.mjs
git diff --cached --stat
git diff --cached
# Run your approved secret scanner here, before commit/push.
git commit -m "Handle empty input and mixed whitespace"
git push -u origin workshop/word-count
```

Open a draft PR through GitHub and include the before/after test statuses. Enable GitHub push protection where available. The sample workflow fetches public source without a supplied credential and runs the three tests on pushes and PRs. Its JavaScript CI does not replace the local coding sandbox.

The exercise does not automatically create a repository, authenticate, push or open a PR. Revoke/rotate a real credential if it is exposed. Removing the latest copy does not undo earlier model context, logs or Git history.

## Skill development and transcript

```sh
./mill run skill-ide --skill skills/coding
./mill run skill-run --skill skills/coding --file examples/snippets/coding.scala
./mill run skill-transcript --skill skills/filesystem \
  --file examples/snippets/transcript-filesystem.scala
```

`skill-transcript` renders the actual scaffold templates and all submitted REPL commands with a normalized per-call UUID. It does not compile or execute the snippet. The final reflective invocation and REPL reset appear as host comments, not fictitious REPL commands.
