---scala
(id = "capture-github-files", seconds = 90, layout = "standard")
---

## Counterexample: GitHub cannot hide borrowed file authority

```scala
val publish: capybara.skills.github.GitHub -> Unit = remote => {
  val topic = remote.branch("capybara/issue-1")
  remote.commit(topic, "Fix", Seq("src/word-count.mjs"))
}
```

`commit` needs `FileSystem`. Resolving it from outer `filesystem` would make this pure function capture file authority. Capture checking rejects that assignment.

```sh
./mill run skill-run --skill github \
  --file examples/snippets/github-hidden-files.scala
```

{{stack footer}}

Source: [public interface](assets/source/github-interface.html), [counterexamples](assets/source/capture-examples.html), [tests](assets/source/github-tests.html)

{{end-stack}}

## Speaker notes

GitHubSkillSuite compiles this counterexample and requires rejection. The explicit contextual parameter in the public interface is what exposes this dependency to the checker. With no FileSystem context at all, commit instead fails implicit resolution.
