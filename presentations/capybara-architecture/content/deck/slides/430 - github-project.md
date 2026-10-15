---scala
(id = "github-project", seconds = 75, layout = "standard")
---

## The actual GitHub demo workspace

```sh
./mill run workshop-setup
. .agent-runtime/demo-workspaces/github.env
```

The command copies both tracked starters and builds the skills. It preserves existing edits. In this checkout the GitHub workspace is:

```text
/Users/jamie/Workspace/capybara-agent/.agent-runtime/demo-workspaces/github
```

The snippet edits `src/word-count.mjs`. Each participant sources their own generated environment file; no manual copying is needed.

{{stack footer}}

Source: [setup command](assets/source/workshop-setup.html), [editing interface](assets/source/coding-interface.html), [implementation](assets/source/coding-implementation.html), [integration tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

Run workshop-setup after cloning the Capybara repository. It prepares coding and GitHub workspaces and validates their builds without invoking capability factories or contacting GitHub. The .env files contain only a safely quoted local path. Repeated setup preserves participant changes; --directory selects a fresh session. The displayed absolute path is this checkout's concrete example; participant commands use portable relative paths. Remote repository and authentication remain separately configured.
