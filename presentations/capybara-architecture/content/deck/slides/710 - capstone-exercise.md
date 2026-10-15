---scala
(id = "capstone-exercise", seconds = 900, layout = "dark-slide")
---

## Exercise 4: compose an issue-to-PR workflow

1. Configure a local clone and host GitHub credentials.
2. Select `universes: ["coding", "github"]`; look up issue #1.
3. Read and edit source using the generic coding capability.
4. Publish named files with GitHub's `commit`, then create a draft PR.
5. Report the exact published commit's CI status.

**Pass condition:** separate capability parameters, rejected cross-skill capture attempts, a reviewed patch and a draft PR.

{{stack footer}}

Source: [GitHub interface](assets/source/github-interface.html), [setup guide](assets/source/github-guide.html), [composed example](assets/source/github-snippet.html)

{{end-stack}}

## Speaker notes

Use examples/snippets/github-lab.scala with skill-run --skill coding,github. Production wiring performs real remote writes. Neither skill includes a local test runner. Functional verification is an independent concern; the sample GitHub CI runs the project tests.
