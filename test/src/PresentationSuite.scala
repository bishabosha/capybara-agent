package capybara.agent

/** Opt-in native renderer check; local Ivy snapshots are not required by normal CI. */
class PresentationSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  test("Scala Reveal build preserves architecture order, transcripts and source links") {
    assume(os.exists(os.pwd / ".agent-runtime/validate-presentation"), "Opt-in local renderer check")
    val root = os.pwd / "presentations/capybara-architecture"
    def loaded(path: String): Skill = Skills.loadFrom(os.pwd / os.RelPath(path)) match
      case steps.result.Result.Ok(value) => value
      case steps.result.Result.Err(error) => fail(error)
    os.write.over(root / "public/assets/repl-transcript.txt",
      SkillTranscript.render(loaded("builtin-skills/capybara-builtins"), loaded("skills/filesystem"),
        "puts(filesystem.list(filesystem.pwd()))"))
    val github = loaded("skills/github")
    for (asset, snippet) <- Seq(
      "github-repl-transcript" -> "github-transcript.scala",
      "github-demo-transcript" -> "github-lab.scala") do
      os.write.over(root / "public/assets" / s"$asset.txt",
        SkillTranscript.renderSkills(loaded("builtin-skills/capybara-builtins"), Seq(github),
          os.read(os.pwd / "examples/snippets" / snippet)))
    val _ = WorkshopSetup.create(os.pwd, os.pwd / ".agent-runtime/demo-workspaces")
    val result = os.proc("scala", "run", (root / "slides.scala").toString,
      "--", "build", "--display").call(check = false, timeout = 150000)
    assertEquals(result.exitCode, 0, result.out.text() + result.err.text())
    val manifest = ujson.read(os.read(root / "dist-display/deck/deck.json"))
    val ids = manifest("slides").arr.map(_("id").str).toVector
    assertEquals(ids.size, 75)
    val ordered = Seq("http-boundary", "context-construction", "context-growth", "agent-loop",
      "skill-build-plan", "interface-compile", "implementation-compile", "cache-classpaths",
      "repl-load", "repl-safe", "repl-entry", "repl-entry-body", "repl-invoke",
      "dev-ide", "dev-snippet", "coding-api", "atomic-skills", "local-api-artifacts",
      "dependency-factory", "github-api", "composed-call", "github-repl-input",
      "github-repl-load", "github-repl-safe", "github-repl-entry", "github-repl-invoke",
      "coding-demo-run", "coding-demo-call", "coding-demo-output", "github-demo-run",
      "github-demo-issue-edit", "publishing", "github-demo-output", "capture-git-exercise",
      "capture-github-files", "exercise-3", "capstone-exercise")
    assert(ordered.forall(ids.contains))
    assertEquals(ordered.map(ids.indexOf(_)), ordered.map(ids.indexOf(_)).sorted)
    val html = os.read(root / "dist-display/deck/index.html")
    assert(html.contains("String -&gt; String"))
    assert(html.contains("source/repl-transcript.html"))
    assert(html.contains("experimental.captureChecking"))
    assert(html.contains("./mill run workshop-setup"))
    assert(html.contains("source .agent-runtime/demo-workspaces/github.env"))
    assert(os.exists(root / "dist-display/deck/speaker-notes.html"))
  }
