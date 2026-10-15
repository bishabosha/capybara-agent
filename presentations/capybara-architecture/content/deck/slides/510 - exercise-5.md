---scala
(id = "exercise-5", seconds = 420, layout = "dark-slide")
---

## Exercise 4: distinguish capture errors from runtime policy

| Snippet | Expected enforcement |
| --- | --- |
| `transformSource(s => { puts(s); s })` | Compiler rejects captured `output` |
| `transformSource(s => coding.read("README.md"))` | Compiler rejects captured `coding` |
| `coding.read("../credentials")` | Backend returns `PATH_DENIED` |
| Write `.github/workflows/ci.yml` | Backend returns `WRITE_DENIED` |
| `coding.sandboxChecks()` | Five fixed names, or `SANDBOX_UNAVAILABLE` |

**Pass condition:** show which failures happen before execution and which require runtime enforcement.


{{stack footer}}

Source: [integration evidence](assets/source/integration-tests.html), [backend policy](assets/source/workshop-backend.html)

{{end-stack}}

## Speaker notes

The table abbreviates coding.transformSource in its first rows. Run snippets independently. Safe compilation does not implement repository path policy or OS isolation. A pure callback restriction is also not an automatic confidentiality label on every string.
