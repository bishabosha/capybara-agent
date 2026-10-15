---scala
(id = "skill-build-plan", seconds = 75, layout = "standard")
---

## Compile and wire skills in dependency order

1. Resolve selected skills and local API dependencies; reject cycles.
2. Compile each interface in safe mode.
3. Publish its API locally under `dev.capybara.skills`.
4. Compile its implementation against its own API and required safe APIs.
5. Construct manifest bindings and pass contexts into the safe method.
6. Compile the combined snippet in safe mode and invoke its trusted caller.

{{stack footer}}

Source: [compiler and cache](assets/source/repl-exec.html), [local artifacts](assets/source/skill-artifacts.html)

{{end-stack}}

## Speaker notes

Only interface artifacts are published; implementations stay in separate cached JARs. Dependency API JAR contents participate in the cache digest, so interface changes invalidate dependent builds. Each implementation can consume another capability through its public interface.
