val Manifest = (
  universe = "coding",
  api = "The harness supplies coding: workshop.Coding. Use coding.read(path), coding.replace(path, expected, next), coding.transformSource(f), coding.test(), coding.diff(), or coding.sandboxChecks(). transformSource requires a pure String -> String callback: do not capture output, coding, or another capability inside it. Read README.md and test/word-count.test.mjs for requirements. Only src/word-count.mjs is writable. test returns a fixed status, never raw logs. Call puts outside pure callbacks to report results. Do not use the filesystem or secrets universe, attempt to read credentials, or publish to GitHub. If WORKSHOP_NOT_CONFIGURED or SANDBOX_UNAVAILABLE occurs, report it; never bypass the policy.",
  predef = "",
  bindings = Vector((name = "coding", tpe = "workshop.Coding", value = "workshop.internal.impl()"))
)
