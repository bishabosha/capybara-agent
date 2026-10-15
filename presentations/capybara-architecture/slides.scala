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
    "github-interface" -> "skills/github/scripts/Interface.scala",
    "github-implementation" -> "skills/github/scripts/Implementation.scala",
    "github-manifest" -> "skills/github/scripts/Manifest.scala",
    "github-tests" -> "test/src/GitHubSkillSuite.scala",
    "composition-tests" -> "test/src/SkillCompositionSuite.scala",
    "repl-exec" -> "src/ReplExec.scala",
    "skill-scaffold" -> "src/SkillScaffold.scala",
    "skill-artifacts" -> "src/SkillArtifacts.scala",
    "skills-loader" -> "src/Skills.scala",
    "scala-agent" -> "src/ScalaAgent.scala",
    "skill-ide" -> "src/SkillIde.scala",
    "skill-runner" -> "src/SkillRunner.scala",
    "workshop-setup" -> "src/WorkshopSetup.scala",
    "github-guide" -> "skills/github/README.md",
    "github-snippet" -> "examples/snippets/github-lab.scala",
    "integration-tests" -> "test/src/WorkshopSuite.scala",
    "skill-transcript" -> "src/SkillTranscript.scala",
    "cli-fixtures" -> "test/src/SkillRunFixture.scala",
    "coding-snippet" -> "examples/snippets/coding.scala",
    "coding-demo-runner" -> "src/CodingDemo.scala"
  ).map { (name, path) => (name, path, os.read(repository / os.RelPath(path))) } ++ Seq(
    ("repl-transcript", "Generated REPL command transcript", os.read(root / "public/assets/repl-transcript.txt")),
    ("coding-demo", "Recorded ordinary coding skill-run stdout", os.read(root / "public/assets/coding-demo.txt")),
    ("github-demo", "Recorded ordinary GitHub skill-run stdout (local HTTP fixture)", os.read(root / "public/assets/github-demo.txt")),
    ("github-repl-transcript", "Generated GitHub commit REPL transcript", os.read(root / "public/assets/github-repl-transcript.txt")),
    ("github-demo-transcript", "Generated full GitHub demo REPL transcript", os.read(root / "public/assets/github-demo-transcript.txt")),
    ("capture-examples", "Rejected capability captures", Seq("coding-capture-output.scala", "github-capture-in-edit.scala", "github-hidden-files.scala")
      .map(name => s"// examples/snippets/$name\n" + os.read(repository / "examples/snippets" / name)).mkString("\n"))
  )
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
