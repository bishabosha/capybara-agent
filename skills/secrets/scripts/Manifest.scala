val Manifest = (
  universe = "secrets",
  api = "val secretAccess: secrets.SecretsInterface =  ...",
  predef = "val secretAccess: secrets.SecretsInterface =  secrets.internal.impl"
)
