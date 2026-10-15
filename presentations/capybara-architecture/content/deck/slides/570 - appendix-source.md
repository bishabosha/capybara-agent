---scala
(id = "appendix-source", seconds = 75, layout = "appendix")
---

## Source tour at a fixed commit

| Question | Source to open |
| --- | --- |
| When does the agent loop continue? | [continueSession](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/scalaAgentDemo.scala#L121-L152) |
| What goes into the model prompt? | [Skills.renderPrompt](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/Skills.scala#L99-L123) |
| How does a manifest become executable code? | [SkillScaffold](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/SkillScaffold.scala#L5-L43) |
| Where does safe mode switch on and off? | [ReplExec.runExpression](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/src/ReplExec.scala#L275-L293) |
| Which rejection examples have tests? | [ReplExecSuite](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/test/src/ReplExecSuite.scala#L134-L157) |
| Where does raw secret data escape? | [SecretsInterface.Dict](https://github.com/bishabosha/capybara-agent/blob/e97130cfd70d9f2a87ef933035e2fd09c7a46b2f/skills/secrets/scripts/Interface.scala#L8-L22) |

Links pin repository snapshot `e97130c`, inspected 15 October 2026.

## Speaker notes

All GitHub source links use the full commit SHA e97130cfd70d9f2a87ef933035e2fd09c7a46b2f and line ranges checked against local git object content. The commit matches origin/main at authoring time. The workshop files are local/unpublished and are not represented by fabricated GitHub links. The canonical build remains slides.scala, using the actual Reveal renderer.
