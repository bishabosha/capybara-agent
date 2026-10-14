val Manifest = (
  universe = "secrets",
  api = "The harness supplies secretAccess: secrets.SecretsInterface as a capability parameter.",
  predef = "",
  bindings = Vector((name = "secretAccess", tpe = "secrets.SecretsInterface", value = "secrets.internal.impl()"))
)
