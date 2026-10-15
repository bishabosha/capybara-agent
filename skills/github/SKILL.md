---
name: github
description: Look up issues, create topic branches, commit and push selected local text files, create draft PRs and report exact-commit CI. Local edits use the separate coding skill, imported through its locally published safe API. Credentials remain inside trusted Scala code.
---

Configure CAPYBARA_GITHUB_REPOSITORY, CAPYBARA_GITHUB_TOKEN and the coding workspace
before starting the agent. See skills/github/README.md. No Python or external
process sandbox is used. The public commit method requires using editing.TextFiles. Its API is imported with using dep; manifest requires supplies the coding runtime context.
