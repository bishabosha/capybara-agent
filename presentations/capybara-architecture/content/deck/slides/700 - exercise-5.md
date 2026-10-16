---scala
(id = "exercise-5", seconds = 420, layout = "dark-slide")
---

## Exercise 3: identify the authority being used

| Snippet | Expected result |
| --- | --- |
| Pure edit callback calls `puts` | Compiler rejection |
| Pure edit callback calls `github` | Compiler rejection |
| `capybara.skills.editing.internal.impl()` | `rejectSafe` rejection |
| `filesystem.read("../credentials")` | Implementation returns `PATH_DENIED` |
| `filesystem.write("notes.txt", "hello")` | Allowed file effect |
| `puts(filesystem.read("example.txt"))` | Allowed release of ordinary text |

**Pass condition:** distinguish unavailable authority, retained authority and deliberately released data.

{{stack footer}}

Source: [editing interface](assets/source/editing-interface.html), [implementation](assets/source/editing-implementation.html), [integration tests](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

Use only synthetic text. Capture checking does not invent a confidentiality label for a returned String. An interface that deliberately returns sensitive strings can release them through output.
