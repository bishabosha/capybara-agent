# Capybara Agent

agentic loop based on the ideas presented in https://github.com/lampepfl/tacit 

## Architecture

```mermaid
flowchart TD
    User["User"] --> Entry["runAgent: CLI query or interactive Terminal"]
    Entry --> Session["runSession / continueSession<br/>Conversation history and request limit"]
    Session --> Agent["ScalaAgent.singleRequest<br/>System prompt and run_scala_code tool"]
    Agent --> Provider["ModelProvider<br/>Select via CAPYBARA_PROVIDER"]

    subgraph Providers["Streaming model clients"]
        Ollama["OllamaClient"]
        Chat["OpenAICompatibleClient<br/>Chat Completions API"]
        Responses["OpenAIResponsesClient<br/>Responses API"]
    end
    Provider --> Ollama
    Provider --> Chat
    Provider --> Responses
    Ollama --> Stream["Shared chunk stream<br/>Text, thinking, tool calls and usage"]
    Chat --> Stream
    Responses --> Stream
    Stream --> Agent

    Agent -->|"run_scala_code: universe + scala_code"| Repl["ReplExec<br/>Compile and execute in Scala REPL"]
    Repl -->|"JSON success or error"| Agent
    Agent -->|"Append assistant messages and tool results"| Session
    Session -->|"Tool calls present: next model request"| Agent
    Session -->|"No tool calls: turn complete"| Entry

    subgraph SkillsRuntime["Skills and execution runtime"]
        Files["skills/ and builtin-skills/<br/>SKILL.md, Manifest, Interface, Implementation"]
        Skills["Skills loader<br/>Basic universe and skill-specific universes"]
        Jars[".agent-runtime/<br/>Cached interface and implementation JARs"]
        Files --> Skills
        Skills -->|"Descriptions and interfaces"| Agent
        Skills -->|"Selected skill and baseline builtins"| Repl
        Repl -->|"Compile skills when needed"| Jars
        Jars -->|"Load JARs and predefs"| Repl
    end

    Agent -->|"Stream output and tool results"| Logger["Logger / RuntimeStatus"]
    Logger --> User
    Session -->|"Record token usage"| Usage["UsageTracker"]
    Entry -->|"/usage"| Usage
    Entry -->|"/x or new query"| Cancel["CancellationToken"]
    Cancel -.-> Agent
    Cancel -.-> Repl
```

The session loop keeps conversation history across interactive turns and makes up
to 16 model requests per turn. `ScalaAgent` exposes a single tool,
`run_scala_code`, whose `universe` selects the basic Scala/Java environment or a
loaded skill. `ReplExec` loads baseline builtins plus any selected skill's JARs
and predefs, then resets the REPL state after each execution. Tool results return
to the model as conversation messages; streamed output goes to the logger.

## How skills work

A skill adds a Scala API to a named execution **universe**. At startup,
`Skills.loadAll` reads skill directories from `skills/`; `ScalaAgent` adds the
`basic` universe and loads `capybara-builtins` separately from `builtin-skills/`.
The model sees each available universe's description, interface, and API
preamble in the system prompt, then selects a universe when calling
`run_scala_code`.

```mermaid
flowchart TD
    subgraph Definition["Skill directory"]
        Md["SKILL.md<br/>YAML name and description"]
        Manifest["scripts/Manifest.scala<br/>universe, api, predef"]
        Interface["scripts/Interface.scala<br/>Public Scala API"]
        Implementation["scripts/Implementation.scala<br/>Runtime implementation"]
    end
    Md --> Load["Skills.loadAll / loadFrom"]
    Manifest --> Load
    Interface --> Load
    Implementation --> Load
    Load --> Skill["Skill metadata and source code"]
    Skill --> Prompt["System prompt<br/>Description, interface and api preamble"]
    Prompt --> Model["Model chooses universe and writes Scala code"]
    Model --> Call["run_scala_code"]
    Call --> Resolve["ScalaAgent resolves universe"]
    Resolve -->|"Unknown universe"| Error["Tool failure"]
    Resolve -->|"Known universe"| Harness["ReplExec.runCodeHarness"]
    Skill --> Compile["Compile on demand<br/>Reuse JARs when source hash matches"]
    Harness --> Compile
    Compile --> Jars["sigs.jar and impl.jar<br/>Baseline builtins plus selected skill"]
    Jars --> REPL["Load into REPL and execute predef"]
    REPL --> Execute["Compile and run generated Scala expression"]
    Execute --> Format["Builtins format final value<br/>JSON success or execution error"]
    Format --> History["Tool result added to conversation"]
    Error --> History
    History --> Model
    Execute --> Reset["Reset REPL state after each call"]
```

Each skill uses four files:

| File | Role |
| --- | --- |
| `SKILL.md` | YAML frontmatter supplies the skill's `name` and `description`. The current prompt renderer uses those fields, rather than the Markdown body. |
| `scripts/Manifest.scala` | Declares `universe` (the tool's selector), `api` (the preamble shown to the model), and optional `predef` (Scala setup code executed before the generated expression). |
| `scripts/Interface.scala` | Defines the public types and methods shown to the model and compiled into `sigs.jar`. |
| `scripts/Implementation.scala` | Implements the API, compiled into `impl.jar`; its source is not included in the model prompt. |

For example, the `filesystem` manifest advertises
`val filesystem: fs.Filesystem = ...` through `api`. Its `predef` actually binds
that value with `val filesystem: fs.Filesystem = fs.internal.impl`. The model can
then call `run_scala_code` with `universe = "filesystem"` and
`scala_code = "filesystem.list(filesystem.pwd())"` without repeating the setup.

Execution compiles the baseline builtins and selected skill as needed, caching
their JARs under `.agent-runtime/interface/<skill-name>/`. The cache hash covers
the Scala version, interface, implementation, API preamble, and predef. Keep the
skill's `name` and `universe` equal: compilation stores JARs by name, while the
REPL loads them by universe. The `basic` universe skips skill-specific JARs and
setup but still loads the baseline builtins for result formatting.

Each call starts from the initial REPL state, loads its APIs, evaluates the
generated expression, and returns a JSON success or failure to the model.
REPL definitions do not persist between calls; conversation history does.
External effects performed by a skill, such as writing a file, are not undone
by resetting the REPL.

## Model providers

Ollama is the default provider, using `qwen3.6:35b-a3b-coding-nvfp4`
at `http://localhost:11434`. Override the model with `CAPYBARA_MODEL`.

To use an OpenAI Chat Completions compatible API through
[sttp-ai](https://github.com/softwaremill/sttp-ai), configure the environment
before launching the agent:

```sh
export CAPYBARA_PROVIDER=openai
export CAPYBARA_BASE_URL=https://your-provider.example/v1
export CAPYBARA_MODEL=your-model-name
export CAPYBARA_API_KEY=your-api-key
./mill run
```

`CAPYBARA_BASE_URL` is the API base URL, including any version or proxy path;
the client appends `/chat/completions`. It defaults to `https://api.openai.com/v1`.
`CAPYBARA_MODEL` is required for either OpenAI-compatible provider. `OPENAI_API_KEY` is also supported,
with `CAPYBARA_API_KEY` taking precedence. Leave both keys unset for local servers
that need no authentication.

For a Responses API endpoint, use `CAPYBARA_PROVIDER=openai-responses` instead.
The client appends `/responses` to the same API base URL. For example, to try
[Muse Spark 1.3 Contributor Free on OpenCode](https://opencode.ai/v2/docs/console/models/),
create an API key in the [OpenCode Console](https://opencode.ai/console/) and run:

```sh
export CAPYBARA_PROVIDER=openai-responses
export CAPYBARA_BASE_URL=https://opencode.ai/inference/v1
export CAPYBARA_MODEL=muse-spark-1.3-contributor-free
export CAPYBARA_API_KEY=your-opencode-api-key
./mill run
```

Use the model ID directly, without an `opencode/` prefix. OpenCode currently lists
this model as a limited-time free contributor offering; its terms allow prompts
and completions to be used for model training. Check the linked model page for
current availability and terms.

The endpoint and model must support streaming and function tools in the selected
API format. Both OpenAI-compatible providers support streamed text, tool calls, conversation history,
cancellation, and token usage when the server returns it. The OpenAI-compatible
request uses standard API fields; Ollama's `think`, sampling options,
`CAPYBARA_CONTEXT_WINDOW`, and `CAPYBARA_KEEP_ALIVE` are not sent to it. The context
window setting still supplies the local `/usage` display; set it to your model's
actual capacity. `/think` controls reasoning for Ollama only.

The Responses provider sends the full conversation with `store=false`, including
completed reasoning items and encrypted reasoning when returned by the endpoint.
It uses `call_id` to pair function calls with their results. Failed, incomplete,
or truncated response streams do not execute pending tool calls.

Provider regression checks for both API formats are in `scripts/test-openai-compatible.scala`.
