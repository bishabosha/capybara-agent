---
name: editing
description: Transform UTF-8 text files and replace expected contents. Uses the filesystem skill for workspace access. Edit callbacks must be pure and cannot retain other capabilities. Independent of language and project layout.
---

The harness supplies `editing: capybara.skills.editing.TextEditor` and automatically
loads `filesystem: capybara.skills.filesystem.FileSystem`. Read and write with
`filesystem`; transform text with `editing`. Both editor methods require the
supplied filesystem context. The editor has no independent file authority.

`CAPYBARA_WORKSPACE_ROOT` selects the host workspace. See `skills/editing/README.md`
and `./mill run editing-demo`.
