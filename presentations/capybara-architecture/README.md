# Capybara Agent architecture and capture-checking workshop

A native `test-blog-renderer` Reveal deck: 75 slides, including two appendices and four exercises. It uses the existing 1600 × 900 theme, fonts, slide fitting, source links, notes and live authoring support. Allow roughly 100–120 minutes including lab setup and discussion.

The order is HTTP and Chat Completions (requests, messages, tools, streaming and context), Capybara architecture, skill compilation/classpaths/settings, a complete single-call REPL transcript, skill development with `skill-ide` and `skill-run`, then capture-checking exercises with generic editing and atomic GitHub operations. After the coding API, the deck introduces multi-skill composition, local API publication, explicit runtime requires and public using TextFiles. A full composed-call transcript precedes both demos. The first exercise follows all explanatory sections.

Core source links pin commit `e97130cfd70d9f2a87ef933035e2fd09c7a46b2f`. New skill and transcript sources have local browser-readable snapshots, refreshed by the Scala build. The transcript uses the actual scaffold templates with a normalized per-call UUID; host invocation and reset are explicitly separate from submitted REPL commands.

## Build and live editing

From this directory inside the Capybara checkout:

```sh
scala run slides.scala -- build --display
scala run slides.scala -- serve --display --port 8766
```

Open `http://127.0.0.1:8766/deck/`. Output is under `dist-display/`; serve that whole output root so `/static/` assets resolve. The script uses the real renderer, including its Markdown templates, typed schema, validation and asset pipeline. There is no Node Markdown build.

For live editing:

```sh
scala run slides.scala -- dev --port 8766
```

The deck is at `/deck/`, Content studio at `/__author/`, and live output in `dist/`. Stop with Ctrl+C. Arrow keys navigate, F toggles fullscreen, O opens overview and S opens speaker view. Speaker view may require popup permission. `/deck/speaker-notes.html` provides ordinary browser notes.

Edit `content/deck/slides/*.md`. `content/deck/index.md` holds metadata and `content/deck/speaker-notes.md` holds notes-page metadata. Slides use native `---scala` front matter, ordered filenames and `## Speaker notes` sections. `public/assets/workshop.css` controls readable code sizing. Displayed Scala is formatted for projection; the linked transcript preserves the exact generated commands. Source snapshots and the transcript are under `public/assets/source/` and `public/assets/repl-transcript.txt`.

The script pins Scala 3.8.3 for the renderer, independently of Capybara's Scala 3.10.0. It resolves `io.github.bishabosha::tiger-site-gen-reveal:0.1.0-SNAPSHOT` from local Ivy; core/live are transitive dependencies. The snapshot is installed on this machine. Other machines need the renderer modules published locally as documented in the sibling renderer project. Compilation and validation in this task use the configured Metals MCP server, following `AGENTS.md`.

## Prebuilt presentation

The tracked `../capybara-architecture.zip` contains the complete ready-to-serve
export: `index.html`, `deck/` and `static/`. Extract it, open a terminal inside
the extracted `capybara-architecture` directory, and run:

```sh
python3 -m http.server 8765 --bind 127.0.0.1
```

Open `http://127.0.0.1:8765/deck/`. The extracted export needs no Scala, Node,
npm or CDN. Rebuilding source snapshots requires the Capybara checkout.

In the source checkout, `index.html`, `deck/`, `static/`, `dist/` and
`dist-display/` are generated output and are ignored by Git. Existing local
exports remain usable for preview. Build and serve `dist-display/` using the
commands above; commit the authored content, public assets, Scala build script
and shareable ZIP. Hashed asset directories should not be committed separately.

## Generic coding demo and capture-checking exercises

Participants run `./mill run workshop-setup` from their Capybara checkout. It
copies both tracked starters, builds the skills, preserves existing edits and
prints actual workspace paths and next commands. Source the generated
`coding.env` or `github.env` to select a demo; credentials are never written there.
Both demos use ordinary `skill-run` commands and checked-in snippets. Coding runs
`examples/snippets/coding.scala`. The GitHub demo uses `skill-run --skill github` with
`examples/snippets/github-lab.scala`; its manifest supplies coding. The deck
shows setup, complete snippets, recorded CLI results, then rejected captures.
The `coding` skill uses Scala/JDK file operations directly, with no Node, Python,
fixed project API or OS process sandbox. The interface, safe compiler and harness
control which capabilities the snippet can use and retain.

See `workshop/README.md` for generic editing examples, capture-checking exercises
and the separate GitHub issue-to-PR workflow. `CAPYBARA_CODING_ROOT` selects the
local editing workspace; it defaults to the process's current directory. A file
capability intentionally releases ordinary text. Secrets remain in separate
trusted APIs rather than being inferred from strings by the capture checker.

`WorkshopSuite` exercises the generic interface through the safe harness.
`CodingDemoSuite` records the ordinary coding CLI run. GitHubSkillSuite records
the GitHub CLI run against a local HTTP fixture with synthetic credentials. `PresentationSuite` is an opt-in native
renderer check: create `.agent-runtime/validate-presentation`, run the demo suite
and renderer suite through MCP, then remove the marker. Normal CI needs no local
renderer snapshot. The GitHub skill is Scala-native and delegates file access to the editing safe API; neither uses a process runner.

## Licenses

The bundled Reveal runtime and Source Sans fonts retain their original license files in the exported `static/reveal_*/vendor/reveal/` and `static/reveal_*/assets/` directories. Theme assets come from the user's `test-blog-renderer` checkout.
