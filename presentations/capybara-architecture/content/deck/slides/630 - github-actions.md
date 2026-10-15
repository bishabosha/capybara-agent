---scala
(id = "github-actions", seconds = 75, layout = "standard")
---

## The lab CI runs without supplied credentials

The starter workflow triggers on `push` and `pull_request`, sets `contents: read`, and fetches its public source without authentication.

```sh
git fetch --depth=1 origin "$SOURCE_SHA"
git checkout --detach FETCH_HEAD
node --test test/word-count.test.mjs
```

The lab needs no secrets or third-party actions. If you add actions, pin reviewed full commit SHAs. Keep untrusted PR code away from privileged `pull_request_target` jobs.

{{stack footer}}

Source: [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use)

{{end-stack}}

## Speaker notes

The exact local workflow is workshop/project/.github/workflows/ci.yml. SOURCE_SHA comes from github.sha and REPO from github.repository. It supports the public synthetic-data lab; a private repo needs a separately reviewed checkout policy. GitHub CI checks the code's behavior. It tests the edited program; the capture checker verifies the agent Scala code and its retained capabilities. This workflow uses no third-party actions or injected secrets, and does not upload arbitrary artifacts.
