---scala
(id = "github-api", seconds = 90, layout = "standard")
---

## GitHub operations borrow file authority only when needed

```scala
trait GitHub extends caps.SharedCapability {
  def lookupIssue(number: Int): Issue
  def createBranch(name: String): Branch
  def commit(branch: Branch, message: String, paths: Seq[String])
      (using files: editing.TextFiles): Branch
  def createPullRequest(branch: Branch,
      title: String, body: String): PullRequest
  def checks(branch: Branch): CheckSummary
}
```

`GitHub` owns remote authority. `commit` also needs the caller’s file authority. The factory does not capture `TextFiles`.

{{stack footer}}

Source: [GitHub interface](assets/source/github-interface.html), [CLI snippet](assets/source/github-snippet.html)

{{end-stack}}

## Speaker notes

The full interface also includes listIssues and branch. Public contextual requirements let capture checking see the extra authority at the call site. Tokens remain private implementation state.
