# Generic text-editing capability

The `editing` skill supplies `editing: capybara.skills.editing.TextEditor` for
`replace(path, expectedCompleteText, nextText)` and `edit(path)(transform)`.
Both methods take a contextual `capybara.skills.filesystem.FileSystem`. The
harness loads the required `filesystem` skill automatically, so snippets use
`filesystem.list/read/write` alongside `editing.edit/replace`.

The editor owns no paths or workspace grant. It reads and writes through the
caller's filesystem capability. Its `String -> String` transform must be pure:
output, filesystem access and other effects belong outside the callback.

## Run the demo

From the Capybara checkout:

Run these steps in the same Bash or Zsh terminal.

**1. Prepare the workshop**

```sh
./mill run workshop-setup
```

**2. Load the workspace settings**

```sh
source .agent-runtime/demo-workspaces/editing.env
```

**3. Run the snippet**

```sh
./mill run skill-run --skill editing --file examples/snippets/editing.scala
```

Setup copies the tracked starter, preserves existing workspaces and builds the
skills. The ordinary snippet lists files, reads and edits
`Greeting.scala`, creates `notes.txt`, and prints the resulting text as structured
stdout. The root remains available in `$CAPYBARA_WORKSPACE_ROOT` for inspection.
No model or GitHub credential is needed.

Run a rejected example separately:

```sh
./mill run skill-run --skill editing --file examples/snippets/editing-capture-output.scala
```

## Publish the safe API locally

```sh
./mill run skill-publish --skill editing
```

This prints the local Ivy `using dep` directive. It checks the skill build and
publishes its safe interface; factories are not invoked.

## Use your own workspace

```sh
export CAPYBARA_WORKSPACE_ROOT=/absolute/path/to/your/project
./mill run skill-ide --skill skills/editing
./mill run skill-run --skill skills/editing --file /path/to/snippet.scala
```

The default workspace is the Capybara process's current directory. The host
selects the root, rather than a model argument. Paths are relative to that root.
`list(".")` lists immediate entries; use `list` and `read` with ordinary Scala
collection operations for discovery and search. All ordinary UTF-8 text files
inside the workspace are available, including new files written by the skill.
Existing parent directories are required for writes. Files are limited to 1 MiB.
The implementation rejects absolute paths, parent traversal and symbolic links.
It writes through a temporary file and atomic replacement. `replace` checks the
complete expected contents before replacing; `edit` uses that same check.

```scala
val before = filesystem.read("README.md")
editing.edit("README.md") { text =>
  text.replace("Old heading", "New heading")
}
filesystem.write("notes.txt", "Remember to update the examples.\n")
puts(filesystem.read("README.md"))
```

## The capture-checking boundary

An edit callback is a pure `String -> String`, so these are rejected:

```scala
editing.edit("README.md") { text =>
  puts(text) // retains output
  text
}

editing.edit("README.md") { text =>
  filesystem.read("other.txt") // retains the file capability
}
```

File operations and reporting are permitted outside the pure callback because
those capabilities were explicitly supplied to the snippet. The checked
interface and safe harness determine which effects are available. The trusted
implementation interprets file paths; the type checker does not inspect path
strings. The filesystem intentionally releases file contents as ordinary
strings. It does not label them confidential or filter credential patterns.
Grant a workspace whose contents may be read and edited; keep credentials in
separate trusted APIs such as the `github` skill's authenticated backend.

`WorkshopSuite` checks generic file operations, path errors, stale replacements,
pure-callback rejections and direct ambient-effect rejections through ReplExec.
`EditingDemoSuite` records the ordinary `skill-run` CLI and checked-in snippet. Run Scala compilation
and tests through Metals MCP as required by `AGENTS.md`.
