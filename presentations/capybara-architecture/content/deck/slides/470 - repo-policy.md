---scala
(id = "repo-policy", seconds = 75, layout = "standard")
---

## The implementation permits three reads and one write

| Request | Implemented policy |
| --- | --- |
| Read README, source or tests | Allowlisted paths and reviewed content |
| Change `src/word-count.mjs` | Expected-content check and atomic replacement |
| Change tests or workflow | `WRITE_DENIED` |
| Read a path escape, symlink or hard link | `PATH_DENIED` |
| Release a canary or recognized credential pattern | `CONTENT_DENIED` |

README and tests must match their frozen baseline. Directory descriptors pin path traversal. Raw OS errors never become tool messages.


{{stack footer}}

Source: [policy implementation](assets/source/workshop-backend.html), [policy tests](assets/source/workshop-tests.html)

{{end-stack}}

## Speaker notes

The implementation checks only known credential patterns and synthetic canaries. It does not establish universal data-flow noninterference. The stronger credential boundary is keeping real credentials out of the source-only workspace and test snapshot. A model could deliberately introduce new source text, but it never receives a real GitHub token through this API.
