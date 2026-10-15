#!/usr/bin/env python3
"""Policy/transport regression tests, invoked through the MUnit MCP suite."""
import base64
import copy
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import urllib.error

import github_workflow as gh
sys.path.insert(0, str(gh.ROOT / "test/fixtures"))
from github_fixture import FakeApi, TOKEN, object_sha

FIX = "export function countWords(text) {\n  const clean = text.trim();\n  return clean === '' ? 0 : clean.split(/\\s+/u).length;\n}\n"


class GitHubWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="capybara-github-policy-")
        self.addCleanup(self.temp.cleanup)
        self.profile = Path(self.temp.name) / "policy.json"
        self.config = {"version": 1, "repository": "example/lab", "base": "main",
            "read": ["README.md", "src/word-count.mjs", "test/word-count.test.mjs"],
            "write": ["src/word-count.mjs"], "allow_untested_draft": False,
            "test": {"kind": "node", "entry": "test/word-count.test.mjs", "executable": shutil.which("node")}}
        gh.save_json(self.profile, self.config)
        self.api = FakeApi("example/lab", TOKEN)
        self.workflow = gh.Workflow(self.profile, TOKEN, lambda *_: self.api)

    def start(self):
        self.workflow.start(1)

    def fix(self):
        self.start()
        self.workflow.replace("src/word-count.mjs", self.workflow.read("src/word-count.mjs"), FIX)

    def verified(self):
        # Policy unit tests set a trusted receipt directly. Separate tests run the real sandbox.
        self.fix()
        self.workflow.state["tested"] = gh.digest(self.workflow.state["files"])

    def denied(self, code, fn):
        with self.assertRaises(gh.Denied) as error:
            fn()
        self.assertEqual(str(error.exception), code)

    def writes(self):
        return [x for x in self.api.data["requests"] if x[0] != "GET"]

    def test_issue_workspace_are_read_only_and_stable(self):
        self.assertEqual(self.workflow.issues(1)[0], "1")
        self.assertEqual(self.workflow.issue(1)[0], "1")
        self.start()
        original = copy.deepcopy(self.workflow.state)
        self.workflow.start(1)
        self.assertEqual(original, self.workflow.state)
        self.assertEqual(self.writes(), [])
        self.denied("SESSION_EXISTS", lambda: self.workflow.start(2))
        self.denied("PATH_DENIED", lambda: self.workflow.read("../credentials"))
        self.denied("WRITE_DENIED", lambda: self.workflow.replace("test/word-count.test.mjs", "", ""))

    def test_plain_encoded_and_pattern_secrets_refused_at_every_release_boundary(self):
        self.start()
        secrets = [TOKEN, TOKEN.encode().hex(), base64.b64encode(TOKEN.encode()).decode(),
                   "LAB_ONLY_CANARY_123", "ghp_" + "a" * 36, "-----BEGIN PRIVATE KEY-----"]
        for secret in secrets:
            with self.subTest(secret=secret[:4]):
                self.denied("CONTENT_DENIED", lambda: self.workflow.replace("src/word-count.mjs",
                    self.workflow.read("src/word-count.mjs"), secret))
                self.denied("CONTENT_DENIED", lambda: self.workflow.push(secret))
                self.denied("CONTENT_DENIED", lambda: self.workflow.pull_request(secret, "body"))
                self.denied("CONTENT_DENIED", lambda: self.workflow.pull_request("title", secret))
        self.assertEqual(self.writes(), [])
        self.api.data["issues"]["1"]["body"] = TOKEN
        self.denied("CONTENT_DENIED", lambda: self.workflow.issue(1))

    def test_reject_unsafe_config_paths_symlinks_and_permissions(self):
        for path in [".env", ".github/workflows/ci.yml", "x/../y", "x//y", "/tmp/x", "secrets/a", "key.pem"]:
            self.denied("PATH_DENIED", lambda: gh.path_name(path))
        link = self.profile.parent / "link.json"
        link.symlink_to(self.profile)
        self.denied("POLICY_DENIED", lambda: gh.private_json(link))
        self.profile.chmod(0o644)
        self.denied("POLICY_DENIED", lambda: gh.private_json(self.profile))

    def test_stale_write_and_test_receipt_cannot_publish(self):
        self.fix()
        self.denied("CONTENT_CHANGED", lambda: self.workflow.replace("src/word-count.mjs", "stale", "x"))
        self.denied("TESTS_REQUIRED", lambda: self.workflow.push("Implement fix"))
        self.assertEqual(self.writes(), [])
        self.workflow.state["tested"] = gh.digest(self.workflow.state["files"])
        self.workflow.replace("src/word-count.mjs", FIX, FIX + "// follow-up\n")
        self.denied("TESTS_REQUIRED", lambda: self.workflow.push("Implement fix"))

    def test_push_and_draft_pr_use_only_pinned_repository_and_approved_patch(self):
        self.verified()
        branch, commit = self.workflow.push("Fix whitespace")
        self.assertEqual(self.workflow.push("Retry"), [branch, commit])
        pr = self.workflow.pull_request("Fix whitespace", "Fixes #1")
        self.assertEqual(pr, ["2", "https://github.com/example/lab/pull/2"])
        self.assertEqual(self.workflow.pull_request("Retry", "Fixes #1"), pr)
        tree = next(x[2] for x in self.writes() if x[1] == "/git/trees")
        self.assertEqual([x["path"] for x in tree["tree"]], ["src/word-count.mjs"])
        request = next(x[2] for x in self.writes() if x[1] == "/pulls")
        self.assertIs(request["draft"], True)
        self.assertIs(request["maintainer_can_modify"], False)
        self.assertNotIn(TOKEN, json.dumps(self.api.data["requests"]))
        self.assertEqual(self.workflow.checks(), [commit, "pending"])
        self.api.data["check_status"] = "passed"
        self.assertEqual(self.workflow.checks(), [commit, "passed"])

    def test_remote_base_and_branch_changes_never_force_push(self):
        self.verified()
        self.api.data["refs"]["main"] = "f" * 40
        self.denied("BASE_CHANGED", lambda: self.workflow.push("Fix"))
        self.assertEqual(self.writes(), [])
        self.api.data["refs"]["main"] = self.workflow.state["base"]
        self.api.data["refs"][self.workflow.state["branch"]] = "f" * 40
        self.denied("BRANCH_CHANGED", lambda: self.workflow.push("Fix"))
        self.assertEqual(self.writes(), [])

    def test_follow_up_is_non_force_and_pr_requires_exact_current_snapshot(self):
        self.verified()
        branch, first = self.workflow.push("Fix")
        self.workflow.replace("src/word-count.mjs", FIX, FIX + "// clarify\n")
        self.denied("PUSH_REQUIRED", lambda: self.workflow.pull_request("Title", "Body"))
        self.workflow.state["tested"] = gh.digest(self.workflow.state["files"])
        _, second = self.workflow.push("Clarify")
        self.assertNotEqual(first, second)
        self.assertEqual(self.api.data["commits"][second]["parents"], [first])
        patch = next(x[2] for x in self.writes() if x[0] == "PATCH")
        self.assertIs(patch["force"], False)
        self.assertEqual(self.api.data["refs"][branch], second)

    def test_ambiguous_ref_creation_is_recovered_without_duplicate_commit(self):
        self.verified()
        original = self.api.request
        def request(method, path="", body=None):
            result = original(method, path, body)
            if method == "POST" and path == "/git/refs":
                raise gh.Denied("GITHUB_UNAVAILABLE")
            return result
        self.api.request = request
        self.denied("GITHUB_UNAVAILABLE", lambda: self.workflow.push("Fix"))
        self.api.request = original
        commit_count = len(self.api.data["commits"])
        branch, commit = self.workflow.push("Retry")
        self.assertEqual(len(self.api.data["commits"]), commit_count)
        self.assertEqual(self.api.data["refs"][branch], commit)

    def test_ambiguous_pr_creation_is_recovered_without_duplicate_pr(self):
        self.verified()
        self.workflow.push("Fix")
        original = self.api.request
        def request(method, path="", body=None):
            result = original(method, path, body)
            if method == "POST" and path == "/pulls":
                raise gh.Denied("GITHUB_UNAVAILABLE")
            return result
        self.api.request = request
        self.denied("GITHUB_UNAVAILABLE", lambda: self.workflow.pull_request("Fix", "Fixes #1"))
        self.api.request = original
        self.assertEqual(self.workflow.pull_request("Fix", "Fixes #1")[0], "2")
        self.assertEqual(len(self.api.data["pulls"]), 1)

    def test_remote_symlink_truncation_and_modified_blob_are_rejected(self):
        original = self.api.request
        def truncated(method, path="", body=None):
            result = original(method, path, body)
            if path.startswith("/git/trees/"):
                result["truncated"] = True
            return result
        self.api.request = truncated
        self.denied("INVALID_RESPONSE", self.start)
        self.api.request = original
        tree = next(iter(self.api.data["trees"].values()))
        entry = next(x for x in tree if x["path"] == "src/word-count.mjs")
        entry["mode"] = "120000"
        self.denied("PATH_DENIED", self.start)
        entry["mode"] = "100644"
        self.api.data["blobs"][entry["sha"]] = "tampered"
        self.denied("INVALID_RESPONSE", self.start)

    def test_credential_in_approved_source_cannot_enter_context_or_workspace(self):
        tree = next(iter(self.api.data["trees"].values()))
        entry = next(x for x in tree if x["path"] == "src/word-count.mjs")
        entry["sha"] = object_sha("blob", TOKEN)
        entry["size"] = len(TOKEN)
        self.api.data["blobs"][entry["sha"]] = TOKEN
        self.denied("CONTENT_DENIED", self.start)
        self.assertIsNone(self.workflow.state)
        self.assertFalse(self.workflow.state_path.exists())
        self.assertEqual(self.writes(), [])

    def test_only_explicit_host_policy_can_allow_untested_drafts(self):
        config = copy.deepcopy(self.config)
        config["test"] = None
        gh.save_json(self.profile, config)
        self.workflow = gh.Workflow(self.profile, TOKEN, lambda *_: self.api)
        self.fix()
        self.denied("TESTS_NOT_CONFIGURED", self.workflow.test)
        self.denied("TESTS_REQUIRED", lambda: self.workflow.push("Fix"))
        self.assertEqual(self.writes(), [])
        self.workflow.state_path.unlink()
        config["allow_untested_draft"] = True
        gh.save_json(self.profile, config)
        self.workflow = gh.Workflow(self.profile, TOKEN, lambda *_: self.api)
        self.fix()
        self.workflow.push("Fix")
        self.workflow.pull_request("Fix", "Fixes #1")
        self.assertIs(self.api.data["pulls"][0]["draft"], True)

    def test_policy_changes_require_a_new_session(self):
        self.start()
        config = copy.deepcopy(self.config)
        config["allow_untested_draft"] = True
        gh.save_json(self.profile, config)
        self.denied("POLICY_DENIED", lambda: gh.Workflow(self.profile, TOKEN, lambda *_: self.api))

    def test_real_http_request_has_fixed_origin_no_proxy_or_redirect_and_fixed_errors(self):
        api = gh.Api("example/lab", TOKEN)
        calls = []
        class Reply:
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def read(self, _): return b'{"number":1}'
        class Opener:
            def open(self, request, timeout):
                calls.append(request)
                return Reply()
        api.opener = Opener()
        self.assertEqual(api.request("GET", "/issues/1"), {"number": 1})
        request = calls[0]
        self.assertEqual(request.full_url, "https://api.github.com/repos/example/lab/issues/1")
        self.assertEqual(request.get_header("Authorization"), "Bearer " + TOKEN)
        self.denied("GITHUB_REQUEST_REJECTED", lambda: gh.NoRedirect().redirect_request(
            request, None, 302, "ignored", {}, "https://attacker.invalid/"))
        self.denied("CONTENT_DENIED", lambda: api.request("POST", "/pulls", {"body": TOKEN}))
        class Broken:
            def open(self, *_args, **_kwargs):
                raise urllib.error.HTTPError("https://invalid/" + TOKEN, 401, TOKEN, {}, None)
        api.opener = Broken()
        self.denied("AUTH_FAILED", lambda: api.request("GET", "/issues/1"))

    @unittest.skipUnless(sys.platform == "darwin", "macOS sandbox required")
    def test_real_sandbox_baseline_fix_receipt_and_no_credential_in_repository_code(self):
        self.start()
        self.assertEqual(self.workflow.test(), ["failed"])
        self.workflow.replace("src/word-count.mjs", self.workflow.read("src/word-count.mjs"), FIX)
        self.assertEqual(self.workflow.test(), ["passed"])
        self.workflow.push("Fix")
        malicious = """import fs from 'node:fs';
import cp from 'node:child_process';
if (process.env.CAPYBARA_GITHUB_TOKEN) throw Error('credential inherited');
try { fs.readFileSync('/etc/passwd'); throw Error('outside read'); }
catch (e) { if (!['EPERM','EACCES'].includes(e.code)) throw e; }
const child = cp.spawnSync('/bin/sh', ['-c', 'exit 0']);
if (!child.error) throw Error('child permitted');
export function countWords(text) { return text.trim() === '' ? 0 : text.trim().split(/\\s+/u).length; }
"""
        self.workflow.replace("src/word-count.mjs", FIX, malicious)
        self.assertEqual(self.workflow.test(), ["passed"])
        self.assertNotIn(TOKEN, self.workflow.diff())


if __name__ == "__main__":
    unittest.main(verbosity=2)
