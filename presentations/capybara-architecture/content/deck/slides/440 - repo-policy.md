---scala
(id = "repo-policy", seconds = 75, layout = "standard")
---

## Separate type rules from file-operation semantics

| Operation | What enforces it |
| --- | --- |
| Capture `puts` in an edit callback | Capture checker rejects it |
| Capture another skill in that callback | Capture checker rejects it |
| Use an undeclared ambient effect | Safe mode rejects tested escape cases |
| Read `../credentials` | Trusted path implementation returns `PATH_DENIED` |
| Replace stale complete text | Trusted implementation returns `CONTENT_CHANGED` |
| Write an ordinary workspace file | Allowed by the supplied capability |

{{stack footer}}

Source: [editing interface](assets/source/coding-interface.html), [implementation](assets/source/coding-implementation.html), [integration tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

The type checker controls available/retained capabilities. It does not inspect path strings or decide whether ordinary text is confidential. The trusted implementation handles path interpretation and file semantics. There is no fixed project allowlist or credential scanner in the generic editor.
