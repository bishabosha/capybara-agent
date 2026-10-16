---
name: github
description: Look up GitHub issues, create topic branches, commit and push selected workspace text files, create draft PRs and report exact-commit CI. Requires filesystem for commit reads; select editing separately to transform files. Credentials remain in trusted host code.
---

The harness supplies `github: capybara.skills.github.GitHub` and the required
`filesystem: capybara.skills.filesystem.FileSystem`. Configure
`CAPYBARA_GITHUB_REPOSITORY`, `CAPYBARA_GITHUB_TOKEN`, and `CAPYBARA_WORKSPACE_ROOT`
on the host. The public commit method requires a filesystem context; the API
imports filesystem's locally published safe interface. The GitHub object holds
no filesystem capability. See `skills/github/README.md`.
