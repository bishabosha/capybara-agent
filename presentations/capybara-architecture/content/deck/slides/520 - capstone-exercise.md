---scala
(id = "capstone-exercise", seconds = 900, layout = "dark-slide")
---

## Exercise 5: the GitHub coding workflow through Capybara

1. Publish the pristine lab to your own public synthetic-data repository. Create branch `workshop/word-count`.
2. Restart Capybara with the workshop environment configured.
3. Ask it to use `coding`, inspect requirements, check the sandbox and reproduce the failure.
4. Apply the fix through a pure `transformSource` callback. Run tests and inspect `diff()`.
5. In a separate trusted terminal, review and scan the staged patch, then push and open a draft PR.

**Pass condition:** rejected capture attempts, `failed` then `passed`, one source-file diff and a reviewed draft PR.


{{stack footer}}

Source: [actual snippet](assets/source/coding-snippet.html), [integration evidence](assets/source/integration-tests.html)

{{end-stack}}

## Speaker notes

A deterministic run uses ./mill run skill-run --skill skills/coding --file examples/snippets/coding.scala. An LLM run uses ./mill run --query with the prompt in workshop/README.md. Use a fresh initialized lab if the deterministic snippet already fixed the file. The skill edits and tests; human GitHub authentication and publication remain outside model authority.
