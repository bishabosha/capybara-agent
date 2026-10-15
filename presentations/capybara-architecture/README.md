# Capybara Agent architecture and capture-checking workshop

A native `test-blog-renderer` Reveal deck: 58 slides, including two appendices and five exercises. It uses the existing 1600 × 900 theme, fonts, slide fitting, source links, notes and live authoring support. Allow roughly 100–120 minutes including lab setup and discussion.

The order is HTTP and Chat Completions (requests, messages, tools, streaming and context), Capybara architecture, skill compilation/classpaths/settings, a complete single-call REPL transcript, skill development with `skill-ide` and `skill-run`, then capture-checking exercises with the implemented coding skill. The first exercise follows all explanatory sections.

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

The bundled `index.html`, `deck/` and `static/` form a ready-to-serve native renderer export. Serve this directory directly:

```sh
python3 -m http.server 8765 --bind 127.0.0.1
```

Open `http://127.0.0.1:8765/deck/`. The exported archive can be served without Scala, Node, npm or a CDN. Rebuilding source snapshots requires the Capybara checkout.

## Runnable GitHub exercise

See `workshop/README.md` for initialization, the agent prompt, deterministic snippet, capture-checking failures and GitHub publishing steps. The `coding` skill is implemented under the repository's `skills/coding`, with a standard-library Python backend under `scripts/workshop.py`. Node is only the runtime for the small JavaScript target repository.

The skill reads and edits approved source, checks pure transformation callbacks, tests a separate read-only snapshot in a macOS sandbox, and returns fixed statuses rather than raw process logs. It does not authenticate or publish to GitHub. Human review and authentication happen in a separate publisher terminal. Unsupported test runtimes fail closed.

`WorkshopSuite` validates the public safe-harness path and backend policies through MCP. `PresentationSuite` is an opt-in local native renderer check: create `.agent-runtime/validate-presentation` before invoking it through MCP, then remove the marker afterward. Normal CI needs no local renderer snapshot.

## Licenses

The bundled Reveal runtime and Source Sans fonts retain their original license files in the exported `static/reveal_*/vendor/reveal/` and `static/reveal_*/assets/` directories. Theme assets come from the user's `test-blog-renderer` checkout.
