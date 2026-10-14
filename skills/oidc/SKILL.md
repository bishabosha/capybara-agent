---
name: oidc
description: Log the user in with an OpenID Connect provider through the device authorization grant (RFC 8628) and call the protected API through an opaque session. Use when the user asks to log in, to check who they are, or to fetch something from the API that needs their identity. A login spans tool calls - start it, show the user the URL and code, then poll status() in a later call. The session value is opaque; never try to print or inspect it.
---

# oidc — device-flow login as a capability, across tool calls

The agent never sees a credential. `start()` begins an RFC 8628 device
authorization grant and returns the verification URL and user code — public
information the agent **shows the user**. In a later tool call `status()`
polls the provider; once it is `Ready`, `session()` hands back an opaque
`Session`. The tokens stay inside the implementation; `get` attaches the
bearer there, and only to a path **relative to the session's API base** (no
scheme, no `//`, no `..`). Redirects are never followed.

```scala
// call 1
auth.start()                     // => Challenge(verificationUri, userCode, expiresInSeconds): show it to the user
// call 2 (after the user says they approved)
auth.status()                    // => Pending | Ready | Expired | Denied | NoLogin
// call 3
val s = auth.session().get       // opaque
auth.whoami(s).body              // the provider's userinfo for that user
auth.get(s, "/some/relative/path")
```

## Why the login survives a tool call

Every `run_scala_code` call starts from a fresh REPL and a fresh class loader
for the skill jars, so a value held in a skill object is gone on the next
call. This skill keeps the login state in a **process-lifetime, JVM-global
registry** (`System.getProperties`, holding a `ConcurrentHashMap` of
strings — JDK types only, so it is independent of any class loader). The
registry is reachable from the trusted implementation only: safe mode does
not let agent code refer to `java.lang.System`. It is an experiment-grade
store: one login at a time, nothing survives the agent process, nothing is
written to disk. Anything beyond that belongs in an external secret keeper
with the same interface.

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
  reflection in agent code, so neither the session nor the registry can be
  reached around the interface.
