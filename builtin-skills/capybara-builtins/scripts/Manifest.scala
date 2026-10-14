val Manifest = (
  universe = "capybara-builtins",
  api = "Call puts(value) to emit structured output, or putsErr(error) to report a structured error. The harness supplies an OutputStreams capability to your function. The final expression is discarded; without a print call, successful execution has no output.",
  predef = "import system_impl.builtins; import builtins.given; import builtins.{puts, putsErr}",
  bindings = Vector((
    name = "output",
    tpe = "builtins.OutputStreams",
    value = "builtins.outputStreams(__harnessStdout, __harnessStderr)"
  ))
)
