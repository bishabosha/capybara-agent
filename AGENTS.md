## Scala Compilation

use `./mill compile`

## Cellar

When you need the API of a JVM dependency, use cellar. Always prefer cellar over hallucinating API signatures.

Cellar is located at `~/.local/bin/cellar`

### Project-aware commands (run from project root)

For querying the current project's code and dependencies (auto-detects build tool):

    cellar get [--module <name>] <fqn>       # single symbol
    cellar list [--module <name>] <package>  # explore a package
    cellar search [--module <name>] <query>  # find by name

- Mill/sbt projects: `--module` is required (e.g. `--module lib`, `--module core`)
- scala-cli projects: `--module` is not supported (omit it)
- `--no-cache`: skip classpath cache, re-extract from build tool
- `--java-home`: override JRE classpath

### External commands (query arbitrary Maven coordinates)

For querying any published artifact by explicit coordinate:

    cellar get-external <coordinate> <fqn>       # single symbol
    cellar list-external <coordinate> <package>  # explore a package
    cellar search-external <coordinate> <query>  # find by name
    cellar get-source <coordinate> <fqn>         # source code
    cellar deps <coordinate>                     # dependency tree

Coordinates must be explicit: `group:artifact_3:version` (use `latest` for newest version).

### Workflow

1. **Don't know the package?** → `cellar search <query>` or `cellar search-external <coordinate> <query>`
2. **Know the package, not the type?** → `cellar list <package>` or `cellar list-external <coordinate> <package>`
3. **Know the type?** → `cellar get <fqn>` or `cellar get-external <coordinate> <fqn>`
4. **Need the source?** → `cellar get-source <coordinate> <fqn>`
