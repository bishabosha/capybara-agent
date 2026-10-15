"""Policy and real macOS process-boundary tests. Invoked by WorkshopSuite through MCP."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("workshop", Path(__file__).with_name("workshop.py"))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)
FIX = "export function countWords(text) {\n  const clean = text.trim();\n  return clean === '' ? 0 : clean.split(/\\s+/u).length;\n}\n"


class WorkshopPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.old_state = w.STATE_DIR
        w.STATE_DIR = self.base / "state"
        self.addCleanup(setattr, w, "STATE_DIR", self.old_state)
        self.root = self.base / "repo"
        w.initialize(self.root)
        self.workspace = w.Workspace(self.root)

    def denied(self, code, fn, *args):
        with self.assertRaises(w.Denied) as error:
            fn(*args)
        self.assertEqual(str(error.exception), code)

    def test_read_replace_diff_and_stale_write(self):
        before = self.workspace.read(w.SOURCE)
        self.assertIn("split(' ')", before)
        self.workspace.replace(w.SOURCE, before, FIX)
        self.assertEqual(self.workspace.read(w.SOURCE), FIX)
        self.assertIn("+  const clean", self.workspace.diff())
        self.denied("CONTENT_CHANGED", self.workspace.replace, w.SOURCE, before, FIX)

    def test_non_allowlisted_paths_and_writes(self):
        for name in ("../credentials", "/etc/passwd", "src/../.env", ".git/config", ".env", "package.json"):
            self.denied("PATH_DENIED", self.workspace.read, name)
        for name in ("test/word-count.test.mjs", ".github/workflows/ci.yml", "../x"):
            self.denied("WRITE_DENIED", self.workspace.replace, name, "", "")

    def test_symlink_leaf_and_parent_and_hardlink(self):
        source = self.root / w.SOURCE
        source.unlink()
        source.symlink_to(self.root / "README.md")
        self.denied("PATH_DENIED", self.workspace.read, w.SOURCE)
        source.unlink()
        os.link(self.root / "README.md", source)
        self.denied("PATH_DENIED", self.workspace.read, w.SOURCE)
        source.unlink()
        source.parent.rmdir()
        source.parent.symlink_to(self.base)
        self.denied("PATH_DENIED", self.workspace.read, w.SOURCE)

    def test_fifo_refused_without_blocking(self):
        source = self.root / w.SOURCE
        source.unlink()
        os.mkfifo(source)
        self.denied("PATH_DENIED", self.workspace.read, w.SOURCE)

    def test_sensitive_source_cannot_enter_read_diff_or_replace(self):
        before = self.workspace.read(w.SOURCE)
        for value in ("LAB_ONLY_CANARY_7F3A", "github_pat_123456789", "ghp_" + "x" * 30,
                      "-----BEGIN PRIVATE KEY-----"):
            self.denied("CONTENT_DENIED", self.workspace.replace, w.SOURCE, before, value)
        (self.root / w.SOURCE).write_text("// LAB_ONLY_CANARY_IN_SOURCE")
        self.denied("CONTENT_DENIED", self.workspace.read, w.SOURCE)
        self.denied("CONTENT_DENIED", self.workspace.diff)

    def test_frozen_tests_cannot_change_and_extra_files_are_never_copied(self):
        (self.root / "test/word-count.test.mjs").write_text("process.exit(0)")
        self.denied("WORKSPACE_CHANGED", self.workspace.read, "test/word-count.test.mjs")
        self.denied("WORKSPACE_CHANGED", self.workspace.snapshot, self.base / "snapshot")

    def test_snapshot_excludes_git_env_and_unapproved_files(self):
        (self.root / ".env").write_text("FAKE=LAB_ONLY_CANARY_NOT_SHARED")
        (self.root / ".git").mkdir()
        (self.root / ".git/config").write_text("fake credential helper")
        dest = self.base / "snapshot"
        self.workspace.snapshot(dest)
        self.assertEqual({p.relative_to(dest).as_posix() for p in dest.rglob('*') if p.is_file()}, set(w.READS))

    def test_oversized_binary_and_control_text(self):
        before = self.workspace.read(w.SOURCE)
        for value in ("x" * (w.LIMIT + 1), "\x00", "\x1b[31m"):
            self.denied("CONTENT_DENIED", self.workspace.replace, w.SOURCE, before, value)
        (self.root / w.SOURCE).write_bytes(b'\xff')
        self.denied("PATH_DENIED", self.workspace.read, w.SOURCE)

    def test_initialization_refuses_nonempty_and_attach_requires_pristine_starter(self):
        self.denied("WORKSPACE_CHANGED", w.initialize, self.root)
        clone = self.base / "clone"
        shutil.copytree(w.STARTER, clone)
        (clone / ".git").mkdir()
        w.initialize(clone, attach=True)
        (clone / ".env").write_text("synthetic")
        self.denied("WORKSPACE_CHANGED", w.initialize, clone, True)

    def test_root_replacement_is_detected(self):
        self.root.rename(self.base / "previous")
        self.root.mkdir()
        self.denied("WORKSPACE_CHANGED", w.Workspace, self.root)

    def test_uninitialized_root_and_unknown_rpc(self):
        self.denied("WORKSHOP_NOT_CONFIGURED", w.Workspace, self.base / "unknown")
        self.denied("INVALID_REQUEST", w.rpc, self.workspace, {"op": "shell"})
        self.denied("PATH_DENIED", self.workspace.read, [])

    @unittest.skipUnless(sys.platform == "darwin", "macOS sandbox runtime")
    def test_real_sandbox_baseline_fix_and_denial_probes(self):
        self.assertEqual(self.workspace.checks(), w.CHECKS)
        self.assertEqual(self.workspace.test(), {"status": "failed"})
        before = self.workspace.read(w.SOURCE)
        self.workspace.replace(w.SOURCE, before, FIX)
        self.assertEqual(self.workspace.test(), {"status": "passed"})

    @unittest.skipUnless(sys.platform == "darwin", "macOS sandbox runtime")
    def test_malicious_module_gets_no_environment_file_network_or_process_authority(self):
        source = r'''
import fs from 'node:fs';
import cp from 'node:child_process';
import net from 'node:net';
if (process.env.GITHUB_TOKEN || process.env.CAPYBARA_API_KEY || process.env.HOME) throw new Error('env visible');
let denied = false;
try { fs.readFileSync(OUTSIDE); } catch (e) { denied = ['EPERM','EACCES'].includes(e.code); }
if (!denied) throw new Error('outside visible');
const child = cp.spawnSync('/bin/sh', ['-c', 'exit 0']);
if (!child.error) throw new Error('subprocess allowed');
const socket = net.connect({host:'127.0.0.1',port:54321});
await new Promise((resolve,reject) => {
 socket.on('connect', () => reject(new Error('network allowed')));
 socket.on('error', e => ['EPERM','EACCES'].includes(e.code) ? resolve() : reject(e));
});
console.log('encoded fake output that must never reach the tool result');
console.error('fake secret in stderr');
export function countWords(text) { const clean = text.trim(); return clean ? clean.split(/\s+/u).length : 0; }
'''.replace('OUTSIDE', json.dumps(str(self.root / ".env")))
        (self.root / ".env").write_text("LAB_ONLY_CANARY_NOT_SHARED")
        self.workspace.replace(w.SOURCE, self.workspace.read(w.SOURCE), source)
        previous = {key: os.environ.get(key) for key in ("GITHUB_TOKEN", "CAPYBARA_API_KEY")}
        try:
            os.environ["GITHUB_TOKEN"] = "LAB_ONLY_CANARY_HOST_TOKEN"
            os.environ["CAPYBARA_API_KEY"] = "LAB_ONLY_CANARY_HOST_PROVIDER"
            self.assertEqual(self.workspace.test(), {"status": "passed"})
        finally:
            for key, value in previous.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    @unittest.skipUnless(sys.platform == "darwin", "macOS sandbox runtime")
    def test_timeout_and_output_flood_remain_bounded_fixed_summaries(self):
        code = "while (true) { console.log('fake output'); }\nexport function countWords() {}\n"
        self.workspace.replace(w.SOURCE, self.workspace.read(w.SOURCE), code)
        self.assertIn(self.workspace.test()["status"], ("failed", "timeout"))

    def test_missing_runtime_fails_closed(self):
        self.workspace.node = "/no/such/node"
        self.denied("SANDBOX_UNAVAILABLE", self.workspace.test)


if __name__ == '__main__':
    unittest.main(verbosity=2)
