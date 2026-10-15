val topic = github.branch("capybara/issue-1")
val pushed = github.commit(topic, "Fix whitespace",
  Seq("src/word-count.mjs"))
puts(pushed)
