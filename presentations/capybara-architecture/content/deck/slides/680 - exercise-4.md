---scala
(id = "exercise-4", seconds = 420, layout = "dark-slide")
---

## Exercise 2: find the bypass in the public API

**7 minutes.** Inspect `SecretsInterface` and its implementation.

1. `accessSecret` demands a pure callback. Find the separate method that still returns raw secret data.
2. Trace that data to the builtin `puts` helper.
3. Does `chatLocal` contact an LLM, or construct a response string?
4. Propose an operation that uses a GitHub token without returning it.

**Pass condition:** name the leaking method, return type and output channel, then draw the revised credential boundary.

{{stack footer}}

Source: [entire public interface](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/secrets/scripts/Interface.scala#L6-L24), [trusted implementation](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/secrets/scripts/Implementation.scala#L7-L25)

{{end-stack}}

## Speaker notes

Answers: Dict.get returns Option[String] and bypasses the accessSecret callback path. Builtin puts can print an Option, whose conversion renders its value or null. chatLocal constructs a string locally. A proposed readIssue(number) can authenticate inside trusted code and return only approved noncredential fields. A pure callback restriction on one method does not compensate for a raw-data getter elsewhere. Never populate this demo with production secrets.
