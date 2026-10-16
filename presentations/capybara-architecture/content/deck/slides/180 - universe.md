---scala
(id = "universe", seconds = 75, layout = "standard")
---

## A call can compose several atomic skills

```json
{"universes": ["editing", "github"], "scala_code": "..."}
```

| Selection | Capability parameters |
| --- | --- |
| `basic` | `output` |
| `editing` | `output`, `editing` |
| `editing` and `github` | `output`, `editing`, `github` |

The resolver loads manifest `requires` contexts once and rejects cycles or duplicate binding names. API dependencies are compiled separately.

{{stack footer}}

Source: [safe harness](assets/source/repl-exec.html), [capability wiring](assets/source/skill-scaffold.html)

{{end-stack}}

## Speaker notes

The legacy universe field remains supported. The universes array selects multiple installed skills. Local API types come from using dep directives; runtime contexts come from manifest requires. The trusted graph is resolved before any snippet executes.
