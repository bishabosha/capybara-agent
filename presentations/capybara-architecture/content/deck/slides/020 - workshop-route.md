---scala
(id = "workshop-route", seconds = 75, layout = "standard")
---

## Architecture first, capture checking in practice

1. HTTP, Chat Completions messages, tool calls and context assembly
2. The agent loop, providers, conversation state and output channels
3. Skill compilation: source staging, JARs, classpaths and compiler settings
4. Every REPL command for one filesystem tool call
5. Skill development: `skill-ide`, then `skill-run`
6. Capture-checking exercises and a runnable GitHub editing workflow

The architecture sections explain existing mechanisms. The exercises use the implemented `editing` skill.

## Speaker notes

The first exercise appears only after the architecture, compilation pipeline, complete transcript and development workflow. Capybara uses Scala 3.10.0. The Reveal renderer uses its own Scala 3.8.3. Node is only the JavaScript exercise project runtime, not the agent implementation. Allow about 100–120 minutes depending on GitHub setup.
