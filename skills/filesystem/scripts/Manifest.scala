val Manifest = (
  universe = "filesystem",
  api = "The harness supplies filesystem: capybara.skills.filesystem.FileSystem for one host-selected workspace. " +
    "Call filesystem.pwd(), filesystem.list(path), filesystem.read(path), and filesystem.write(path, text). " +
    "Example snippet body: puts(filesystem.list(filesystem.pwd())). " +
    "Paths are workspace-relative; absolute paths, parent traversal and symlinks are denied. " +
    "Reads and writes use UTF-8 text with a 1 MiB limit; writes require existing parent directories. " +
    "Reading releases ordinary printable text, so the host must exclude credentials from the workspace. " +
    "For pure text transformations and checked replacements, select editing, which depends on filesystem. " +
    "Report file errors without attempting another skill to bypass the workspace grant.",
  predef = "",
  bindings = Vector((name = "filesystem", tpe = "capybara.skills.filesystem.FileSystem", value = "capybara.skills.filesystem.internal.impl()"))
)
