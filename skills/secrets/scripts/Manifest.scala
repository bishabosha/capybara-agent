val Manifest = (
  universe = "secrets",
  api = "The harness supplies secrets: capybara.skills.secrets.SecretAccess as a capability parameter.",
  predef = "",
  bindings = Vector(
    (name = "secrets", tpe = "capybara.skills.secrets.SecretAccess", value = "capybara.skills.secrets.internal.impl()")
  )
)
