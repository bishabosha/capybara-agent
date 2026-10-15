val publish: githubskill.GitHub -> Unit = remote => {
  val topic = remote.branch("capybara/issue-1")
  remote.commit(topic, "Fix", Seq("src/word-count.mjs"))
}
