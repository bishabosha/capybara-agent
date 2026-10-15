---scala
(id = "local-api-artifacts", seconds = 75, layout = "standard")
---

## Publish safe APIs under one local organization

```sh
./mill run skill-publish --skill coding
./mill run skill-publish --skill github
```

```scala
//> using repository ivy2local
//> using dep "dev.capybara.skills::coding-api:0.1.0-SNAPSHOT"
```

The command publishes checked APIs to local Ivy, dependencies first, and prints these directives. Factories are never invoked. Implementations remain separate.

The local artifact supplies types. Manifest wiring supplies runtime capabilities.

{{stack footer}}

Source: [dependency resolution](assets/source/skills-loader.html), [local publication](assets/source/skill-artifacts.html), [composition tests](assets/source/composition-tests.html)

{{end-stack}}

## Speaker notes

These are implemented mechanisms validated through the real safe harness and local Ivy publication. Factory code is trusted. The generated snippet body remains in safe mode.
