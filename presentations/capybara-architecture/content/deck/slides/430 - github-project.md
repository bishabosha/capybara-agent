---scala
(id = "github-project", seconds = 75, layout = "standard")
---

## The actual GitHub demo workspace

**1. Prepare both workspaces**

```sh
./mill run workshop-setup
```

**2. Load the GitHub workspace into this terminal**

```sh
source .agent-runtime/demo-workspaces/github.env
```

Setup preserves edits. It creates this workspace in the current checkout:

```text
/Users/jamie/Workspace/capybara-agent/.agent-runtime/demo-workspaces/github
```

Run the steps separately, in order, in the same Bash or Zsh terminal.

{{stack footer}}

Source: [setup command](assets/source/workshop-setup.html), [editing interface](assets/source/editing-interface.html), [implementation](assets/source/editing-implementation.html), [integration tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

Run workshop-setup after cloning the Capybara repository. It prepares editing and GitHub workspaces and validates their builds without invoking capability factories or contacting GitHub. The .env files contain only a safely quoted local path. Repeated setup preserves participant changes; --directory selects a fresh session. The displayed absolute path is this checkout's concrete example; participant commands use portable relative paths. Remote repository and authentication remain separately configured.
