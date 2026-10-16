# Workspace filesystem

`filesystem: capybara.skills.filesystem.FileSystem` supplies `pwd()`, `list(path)`,
`read(path)` and `write(path, text)`. Paths are strings relative to the host-selected
workspace; `pwd()` returns `"."`. Set `CAPYBARA_WORKSPACE_ROOT` before launching the
harness. `CAPYBARA_CODING_ROOT` is accepted as a legacy fallback.

Reads and writes use UTF-8 with a 1 MiB limit. Absolute paths, parent traversal,
and symlinks are rejected. Writes require existing parent directories and replace
the destination atomically while preserving existing POSIX permissions.

```scala
puts(filesystem.list(filesystem.pwd()))
val text = filesystem.read("README.md")
filesystem.write("notes.txt", text)
```

Select `editing` for `edit` and expected-content `replace`. Its `TextEditor` uses
this public API with a contextual `FileSystem`; it has no independent workspace
or file access. GitHub also requires `FileSystem` to read selected commit paths,
without depending on the editor.
