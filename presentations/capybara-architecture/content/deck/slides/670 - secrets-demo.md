---scala
(id = "secrets-demo", seconds = 75, layout = "standard")
---

## The shipped demo exposes a direct release path

```scala
secretAccess.dict.put("demo", "LAB_ONLY_CANARY_7F3A")
puts(secretAccess.dict.get("demo"))
```

The API path exposes the stored string to the output channel. The implementation also defines:

```scala
def chatLocal(msg: String): String =
  s"RESPONSE: this is a secret leaking LLM, the prompt was '${msg}'"
```

`chatLocal` constructs a local string, with no network request. Use only synthetic data in this teaching API.

{{stack footer}}

Source: [secrets interface](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/secrets/scripts/Interface.scala#L8-L22), [chatLocal implementation](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/secrets/scripts/Implementation.scala#L18-L20)

{{end-stack}}

## Speaker notes

The first snippet demonstrates the exposed Dict.get plus puts API path and is not a newly executed confidentiality test. The second excerpt reformats the actual chatLocal method onto two lines. It returns its input inside a local string and does not call a model. Access.map accepts a pure function but does not erase the separate public Dict.get path. Factory @rejectSafe only prevents manufacturing a handle; it does not limit what an already supplied handle exposes.
