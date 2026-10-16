---
name: filesystem
description: List workspace directories and read or write UTF-8 text files using workspace-relative paths. Use editing for pure text transformations and checked replacements. No shell or arbitrary host filesystem access.
---

The harness supplies `filesystem: capybara.skills.filesystem.FileSystem`.
`CAPYBARA_WORKSPACE_ROOT` selects the workspace; it defaults to the current directory.
`CAPYBARA_CODING_ROOT` remains a legacy fallback for existing host setups.
Absolute paths, parent traversal and symbolic links are rejected. Reads and writes
are limited to 1 MiB of UTF-8 text. Parent directories must already exist.
