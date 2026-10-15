---scala
(id = "github-project", seconds = 75, layout = "standard")
---

## Initialize the real workshop workspace

From the Capybara checkout, before starting the agent:

```sh
python3 scripts/workshop.py init --repo /tmp/capybara-word-count-lab
export CAPYBARA_WORKSHOP_ROOT=/tmp/capybara-word-count-lab
export CAPYBARA_WORKSHOP_NODE="$(command -v node)"
python3 scripts/workshop.py check --repo "$CAPYBARA_WORKSHOP_ROOT"
```

The initializer copies the clean starter and records its baseline outside the workspace. It refuses a nonempty destination. `attach` supports a pristine clone of the exact starter.

The test backend requires macOS, Python and native Node. Node runs the JavaScript sample; the agent and capability API are Scala.


{{stack footer}}

Source: [initializer and sandbox](assets/source/workshop-backend.html)

{{end-stack}}

## Speaker notes

A host config variable selects the fixed root, never a model request. State lives under the agent’s ignored .agent-runtime/workshop. The helper refuses an unregistered or replaced root. Git metadata is never included in the test snapshot. Run the backend from the agent checkout, where the trusted script and baseline live.
