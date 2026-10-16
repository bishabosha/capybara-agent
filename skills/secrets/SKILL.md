---
name: secrets
description: Demonstrate capture-checked access to an in-memory secret dictionary and a local mock chat. Use for capability-confinement examples; not a persistent secret manager or real chat service.
---

The harness supplies `secrets: capybara.skills.secrets.SecretAccess`.
Secret access callbacks use the capability wrapper `Access`; their pure function
cannot retain output or other capabilities.
