// Select universes ["coding", "github"]. This performs real writes with production wiring.
puts(github.lookupIssue(1))
val topic = github.createBranch("capybara/issue-1")
coding.edit("src/word-count.mjs") { source =>
  source.replace("return text.split(' ').length;",
    """const clean = text.trim();
  return clean === '' ? 0 : clean.split(/\s+/u).length;""")
}
puts(coding.read("src/word-count.mjs"))
val pushed = github.commit(topic, "Handle empty input and mixed whitespace",
  Seq("src/word-count.mjs"))
puts(github.createPullRequest(pushed,
  "Handle empty input and mixed whitespace", "Fixes #1"))
puts(github.checks(pushed))
