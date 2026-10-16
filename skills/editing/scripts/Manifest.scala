val Manifest = (
  universe = "editing",
  api = "The harness supplies editing: capybara.skills.editing.TextEditor and its required " +
    "filesystem: capybara.skills.filesystem.FileSystem. " +
    "Use filesystem.list/read/write for basic file operations. " +
    "Call editing.replace(path, expectedCompleteText, nextText) or editing.edit(path)(transform). " +
    "Both use the supplied filesystem context automatically; the editor owns no workspace authority. " +
    "Example snippet body: editing.edit(\"README.md\")(_.replace(\"old\", \"new\")); puts(filesystem.read(\"README.md\")). " +
    "The transform is a pure String -> String callback: do not capture filesystem, editing, output, " +
    "or another capability inside it. Call puts outside the callback. " +
    "This is language-independent text editing, with no subprocesses, tests, Git operations or project-specific helpers.",
  predef = "",
  bindings = Vector((name = "editing", tpe = "capybara.skills.editing.TextEditor", value = "capybara.skills.editing.internal.impl()")),
  requires = Vector("filesystem")
)
