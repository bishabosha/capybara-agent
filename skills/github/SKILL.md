---
name: github
description: Complete an issue-to-draft-PR development workflow in a host-approved GitHub repository. Read approved source, implement and test changes, publish a topic branch and open a draft PR without receiving credentials. Pure edit callbacks reject captured capabilities. Runtime policy restricts files, destinations and outgoing content.
---

Configure a profile with `python3 scripts/github_workflow.py init --help`, then set
`CAPYBARA_GITHUB_PROFILE` and the host-only `CAPYBARA_GITHUB_TOKEN` before starting
Capybara. See `skills/github/README.md` for the runnable lab and security boundary.
