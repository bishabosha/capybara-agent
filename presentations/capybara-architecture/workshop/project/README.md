# Word count workshop

Fix `countWords(text)` so empty input has zero words and consecutive whitespace, tabs and newlines separate words without adding empty entries. Input is always a string. This exercise counts whitespace-delimited tokens rather than linguistic words.

Run `node --test test/word-count.test.mjs` with Node 20 or newer. No install step or secrets are required. The starter intentionally fails two of its three tests.

Change only `src/word-count.mjs`. Keep the existing tests intact. Submit a draft PR with a concise description and test evidence.

The sample GitHub Actions workflow is for a **public synthetic-data workshop repository**. It fetches source without authentication and uses no third-party actions. For private repos, replace it with a separately reviewed checkout setup. This CI job checks functionality, not the agent sandbox's security policy.
