---scala
(id = "publishing", seconds = 75, layout = "standard")
---

## The human publisher holds GitHub authentication

The implemented coding capability reads, edits, tests and produces a diff. It has no token getter, arbitrary shell, HTTP client or push operation.

```sh
git add src/word-count.mjs
git diff --cached
# Run the approved secret scanner before publishing.
git commit -m "Handle empty input and mixed whitespace"
git push -u origin workshop/word-count
```

These commands run in the human publisher's terminal, after review. GitHub push protection adds supported-pattern scanning at push time.


{{stack footer}}

Source: [GitHub push protection](https://docs.github.com/en/code-security/concepts/secret-security/push-protection), [capability surface](assets/source/coding-interface.html)

{{end-stack}}

## Speaker notes

The GitHub repository is a participant-owned exercise project, not created or pushed automatically by this work. Keep credentials out of remote URLs and source files. An approved diff is intentionally public code, while authentication stays in the trusted publisher.
