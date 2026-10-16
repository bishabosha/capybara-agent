---
name: inspect-scala-api
description: Inspect Scala skill APIs. Retrieve signatures, browse packages, and find fully qualified symbols. Use before calling an unfamiliar supplied capability. Powered by Cellar.
---

The harness supplies `inspectScalaApi: capybara.skills.inspectscalaapi.ScalaApiInspector`.

Select `inspect-scala-api` together with the skill whose API you need. The harness prepares
that skill's `sigs.jar` and publishes its safe API to local Ivy. Cellar reads the
published API, which excludes implementation factories.

The harness prepares a pinned JVM Cellar runtime on first use through Coursier
(`cs` on PATH), using TASTy Query 1.10.0 and the harness's Scala compiler/inspector
version. Cached dependencies are reused. The runtime's own telemetry is disabled;
the user's installed Cellar and its settings are unchanged.
`CAPYBARA_CELLAR_BIN` explicitly overrides this with a host-selected executable.
This capability accepts installed skill names, not arbitrary Maven coordinates,
JAR paths, shell commands, or source-code requests.
