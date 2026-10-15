//> using scala "3.8.3"
//> using options -experimental -preview
//> using repository ivy2local
//> using dep "io.github.bishabosha::tiger-site-gen-reveal:0.1.0-SNAPSHOT"

import live.LiveSite
import model.SiteRoot
import revealTheme.{DeckPage, RevealTheme}

// RevealTheme owns the typed deck schema, Markdown templates, layouts and assets.
// Sources: content/deck/. Outputs: dist/ (live), dist-display/ (static).
private val theme = RevealTheme(page = DeckPage(stylesheets = Seq("assets/workshop.css")))

private def prepareSourceLinks(root: os.Path): Unit =
  val repository = root / os.up / os.up
  val files = Seq(
    "coding-interface" -> "skills/coding/scripts/Interface.scala",
    "coding-implementation" -> "skills/coding/scripts/Implementation.scala",
    "coding-manifest" -> "skills/coding/scripts/Manifest.scala",
    "workshop-backend" -> "scripts/workshop.py",
    "workshop-tests" -> "scripts/test_workshop.py",
    "integration-tests" -> "test/src/WorkshopSuite.scala",
    "skill-transcript" -> "src/SkillTranscript.scala",
    "coding-snippet" -> "examples/snippets/coding.scala"
  ).map { (name, path) => (name, path, os.read(repository / os.RelPath(path))) } :+
    ("repl-transcript", "Generated REPL command transcript", os.read(root / "public/assets/repl-transcript.txt"))
  def escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
  for (name, title, text) <- files do
    val lines = text.linesIterator.zipWithIndex.map { (line, i) =>
      s"<li id='L${i + 1}'><code>${escape(line)}</code></li>"
    }.mkString("\n")
    val html = s"""<!doctype html><html lang="en"><meta charset="utf-8">
      |<title>${escape(title)}</title><style>body{font:18px/1.5 system-ui;margin:40px;background:#f5f2eb;color:#19242a}
      |code{white-space:pre-wrap;overflow-wrap:anywhere}li:target{background:#fff0b3}a{color:#c92f39}li{padding-left:12px}</style>
      |<p><a href="/deck/">Back to deck</a></p><h1>${escape(title)}</h1><ol>$lines</ol></html>""".stripMargin
    os.write.over(root / "public/assets/source" / s"$name.html", html, createFolders = true)

/** dev (default), build --display, or serve --display --port 8766. */
@main def slides(args: String*): Unit =
  val root = SiteRoot.here
  prepareSourceLinks(root.root)
  val site = LiveSite(theme, RevealTheme.liveSettings("deck"))(using root)
  site.main(args)
