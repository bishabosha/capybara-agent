package capybara.agent

/** Opt-in native renderer check; local Ivy snapshots are not required by normal CI. */
class PresentationSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  test("Scala Reveal build preserves architecture order, transcripts and source links") {
    assume(os.exists(os.pwd / ".agent-runtime/validate-presentation"), "Opt-in local renderer check")
    val root = os.pwd / "presentations/capybara-architecture"
    val result = os.proc("scala", "run", (root / "slides.scala").toString,
      "--", "build", "--display").call(check = false, timeout = 150000)
    assertEquals(result.exitCode, 0, result.out.text() + result.err.text())
    val manifest = ujson.read(os.read(root / "dist-display/deck/deck.json"))
    val ids = manifest("slides").arr.map(_("id").str).toVector
    assertEquals(ids.size, 58)
    val ordered = Seq("http-boundary", "context-construction", "context-growth", "agent-loop",
      "skill-build-plan", "interface-compile", "implementation-compile",
      "cache-classpaths", "repl-load", "repl-safe", "repl-entry", "repl-entry-body", "repl-invoke",
      "dev-ide", "dev-snippet", "exercise-3", "capture-git-exercise", "capstone-exercise")
    assertEquals(ordered.map(ids.indexOf(_)), ordered.map(ids.indexOf(_)).sorted)
    val html = os.read(root / "dist-display/deck/index.html")
    assert(html.contains("String -&gt; String"))
    assert(html.contains("source/repl-transcript.html"))
    assert(html.contains("experimental.captureChecking"))
    assert(os.exists(root / "dist-display/deck/speaker-notes.html"))
  }
