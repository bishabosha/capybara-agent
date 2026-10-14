---
name: oidc
description: Log the user in with an OpenID Connect provider through the device authorization grant (RFC 8628) and call the protected API through an opaque session. Use when the user asks to log in, to check who they are, or to fetch something from the API that needs their identity. The session value is opaque; never try to print or inspect it.
---

# oidc — device-flow login as a capability

The agent never sees a credential. `login()` starts an RFC 8628 device
authorization grant with the configured provider, prints the verification
URL and the user code on the agent's terminal, waits until the user has
approved the code in a browser, and returns an opaque `Session`. The tokens
stay inside the implementation; `get` attaches the bearer there, and only
to a path **relative to the session's API base** (no scheme, no `//`, no
`..`). Redirects are never followed.

Because every tool call starts from a fresh REPL, the session lives for one
tool call: log in and use the session in the **same** `run_scala_code` call.

```scala
val s = auth.login()           // blocks until the user approves
auth.whoami(s).body            // the provider's userinfo for that user
auth.get(s, "/some/relative/path")
```

## Configuration (environment of the agent process)

| Variable | Required | Meaning |
|---|---|---|
| `OIDC_ISSUER` | yes | Issuer URL; discovery is read from `<issuer>/.well-known/openid-configuration` |
| `OIDC_CLIENT_ID` | yes | A *public* client with the device authorization grant enabled |
| `OIDC_SCOPE` | no | Default `openid profile email` |
| `OIDC_API_BASE` | no | The one base URL a session may call; default: the issuer |

Works with any provider that publishes `device_authorization_endpoint` and
`token_endpoint` in its discovery document and allows the device grant for a
public client.

## What the type system enforces

- The interface is compiled under safe mode, the implementation is sealed
  with `@assumeSafe val impl`; agent code can only call what `oidc.Oidc`
  declares.
- `Session` is an abstract type: no members, no extractor, not a `Product`,
  `toString` is `Session(****)`. There is no token value agent code can name.
- Safe mode rejects `println`, `scala.Console`, `java.*`, `asInstanceOf` and
  reflection in agent code, so the session cannot be exfiltrated around the
  interface either.
