val Manifest = (
  universe = "coding",
  api = "The harness supplies coding: editing.TextFiles for the host-selected workspace. Use list(path), read(path), write(path, text), replace(path, expectedCompleteText, nextText), and edit(path)(transform). Paths are workspace-relative and existing parent directories are required for writes. edit takes a pure String -> String callback: do not capture coding, output, or another capability inside it. Call puts outside the callback. This is a general text editor with no project-specific paths, subprocesses, test runner, Git operations or Python helper. Reading returns ordinary printable text; the workspace grant must exclude credentials. Report path or file errors without attempting another universe to bypass the workspace grant.",
  predef = "",
  bindings = Vector(
    (
      name = "coding",
      tpe = "editing.TextFiles",
      value = "editing.internal.impl()"
    )
  )
)
