val Manifest = (
  universe = "inspect-scala-api",
  api = "The harness supplies inspectScalaApi: capybara.skills.inspectscalaapi.ScalaApiInspector. Select universes = [\"inspect-scala-api\", " +
    "\"editing\"] to inspect editing, or replace editing with another installed skill. " +
    "This prepares its public API before Cellar reads it. " +
    "Example scala_code: puts(inspectScalaApi.get(\"editing\", \"capybara.skills.editing.TextEditor\")). " +
    "Use inspectScalaApi.search(skill, query) when you do not know the symbol, " +
    "inspectScalaApi.list(skill, packageOrType) to browse, and inspectScalaApi.get(skill, symbol) for signatures. " +
    "Read the skill's calling instructions first for its supplied capability names. " +
    "Cellar describes existing APIs; do not implement the returned declarations. " +
    "It reads only published safe skill APIs, never implementation JARs or factories. " +
    "The host prepares a pinned Cellar runtime compatible with these API JARs. " +
    "Do not guess method names or invent reflection helpers; use inspectScalaApi.list or inspectScalaApi.search to find a symbol.",
  predef = "",
  bindings = Vector((name = "inspectScalaApi", tpe = "capybara.skills.inspectscalaapi.ScalaApiInspector", value = "capybara.skills.inspectscalaapi.internal.impl()"))
)
