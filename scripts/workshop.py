#!/usr/bin/env python3
"""Trusted backend for the coding skill. No repository-supplied commands or Python imports."""
import argparse
import base64
import difflib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile

AGENT_ROOT = Path(__file__).resolve().parent.parent
STARTER = AGENT_ROOT / "presentations/capybara-architecture/workshop/project"
STATE_DIR = AGENT_ROOT / ".agent-runtime/workshop"
SOURCE = "src/word-count.mjs"
READS = frozenset(("README.md", SOURCE, "test/word-count.test.mjs"))
LIMIT = 16384
CHECKS = ["environment-cleared", "outside-read-denied", "outside-write-denied",
          "network-denied", "other-executable-denied"]
SECRET = re.compile(r"LAB_ONLY_CANARY_|github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]{16,}|"
                    r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bsk-[A-Za-z0-9_-]{16,}|"
                    r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")


class Denied(Exception):
    """Only fixed codes, never original contents, paths, logs or OS error messages."""


def safe_text(value):
    if not isinstance(value, str) or len(value.encode("utf-8")) > LIMIT:
        raise Denied("CONTENT_DENIED")
    if SECRET.search(value) or any(ord(c) < 32 and c not in "\n\r\t" for c in value):
        raise Denied("CONTENT_DENIED")
    return value


def state_path(root):
    return STATE_DIR / (hashlib.sha256(str(root).encode()).hexdigest() + ".json")


def initialize(repo, attach=False):
    root = Path(repo).expanduser().absolute()
    if root.is_symlink():
        raise Denied("PATH_DENIED")
    root = root.resolve()
    if root == AGENT_ROOT or AGENT_ROOT in root.parents:
        raise Denied("PATH_DENIED")
    templates = {p.relative_to(STARTER).as_posix(): p.read_bytes()
                 for p in STARTER.rglob("*") if p.is_file()}
    for rel in READS:
        safe_text(templates[rel].decode("utf-8"))
    if attach:
        # Only the pristine starter is attachable. Ignore real .git metadata, never read it.
        if not root.is_dir():
            raise Denied("WORKSPACE_CHANGED")
        found = set()
        for directory, dirs, files in os.walk(root, followlinks=False):
            current = Path(directory)
            for name in list(dirs):
                p = current / name
                if p.is_symlink():
                    raise Denied("PATH_DENIED")
                if current == root and name == ".git":
                    dirs.remove(name)
            for name in files:
                p = current / name
                rel = p.relative_to(root).as_posix()
                if p.is_symlink() or rel not in templates or p.read_bytes() != templates[rel]:
                    raise Denied("WORKSPACE_CHANGED")
                found.add(rel)
        if found != set(templates):
            raise Denied("WORKSPACE_CHANGED")
    else:
        if root.exists() and any(root.iterdir()):
            raise Denied("WORKSPACE_CHANGED")
        root.mkdir(parents=True, exist_ok=True)
        for rel, data in templates.items():
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    info = root.stat()
    state = {"root": str(root), "device": info.st_dev, "inode": info.st_ino,
             "baseline": templates[SOURCE].decode(),
             "frozen": {rel: templates[rel].decode() for rel in READS if rel != SOURCE}}
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = state_path(root)
    # Do not silently reset an existing lab's baseline or trust changed source templates.
    if target.exists() and json.loads(target.read_text()) != state:
        raise Denied("WORKSPACE_CHANGED")
    target.write_text(json.dumps(state))
    target.chmod(0o600)
    return str(root)


class Workspace:
    def __init__(self, repo, node="node"):
        self.root = Path(repo).expanduser().absolute()
        if self.root.is_symlink():
            raise Denied("PATH_DENIED")
        self.root = self.root.resolve()
        try:
            self.state = json.loads(state_path(self.root).read_text())
            info = self.root.stat()
            if (self.state["root"], self.state["device"], self.state["inode"]) != (
                    str(self.root), info.st_dev, info.st_ino):
                raise Denied("WORKSPACE_CHANGED")
        except (OSError, ValueError, KeyError):
            raise Denied("WORKSHOP_NOT_CONFIGURED") from None
        self.node = node

    def parent_fd(self, rel):
        # Every component is opened relative to an already pinned directory descriptor.
        # Reject symlinks, devices/FIFOs and hard links rather than following path races.
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            if (info.st_dev, info.st_ino) != (self.state["device"], self.state["inode"]):
                raise Denied("WORKSPACE_CHANGED")
            for part in rel.split("/")[:-1]:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = child
            return fd
        except BaseException:
            os.close(fd)
            raise

    def read(self, rel):
        if not isinstance(rel, str) or rel not in READS:
            raise Denied("PATH_DENIED")
        parent = None
        fd = None
        try:
            parent = self.parent_fd(rel)
            fd = os.open(rel.split("/")[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > LIMIT:
                raise Denied("PATH_DENIED")
            data = os.read(fd, LIMIT + 1).decode("utf-8")
            if rel in self.state["frozen"] and data != self.state["frozen"][rel]:
                raise Denied("WORKSPACE_CHANGED")
            return safe_text(data)
        except (OSError, UnicodeError):
            raise Denied("PATH_DENIED") from None
        finally:
            if fd is not None:
                os.close(fd)
            if parent is not None:
                os.close(parent)

    def replace(self, rel, expected, next_value):
        if rel != SOURCE:
            raise Denied("WRITE_DENIED")
        safe_text(expected)
        safe_text(next_value)
        if self.read(rel) != expected:
            raise Denied("CONTENT_CHANGED")
        parent = None
        temp = ".capybara-" + os.urandom(12).hex()
        try:
            parent = self.parent_fd(rel)
            fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
            with os.fdopen(fd, "wb") as out:
                out.write(next_value.encode())
                out.flush()
                os.fsync(out.fileno())
            os.replace(temp, rel.split("/")[-1], src_dir_fd=parent, dst_dir_fd=parent)
        except OSError:
            raise Denied("WRITE_DENIED") from None
        finally:
            if parent is not None:
                try:
                    os.unlink(temp, dir_fd=parent)
                except FileNotFoundError:
                    pass
                os.close(parent)

    def diff(self):
        before = safe_text(self.state["baseline"])
        after = self.read(SOURCE)
        result = "".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                            fromfile="a/" + SOURCE, tofile="b/" + SOURCE))
        return safe_text(result)

    def snapshot(self, path):
        for rel in READS:
            data = self.read(rel)
            dest = path / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(data)

    def test(self):
        with tempfile.TemporaryDirectory(prefix="capybara-test-") as temp:
            snapshot = Path(temp).resolve() / "project"
            self.snapshot(snapshot)
            runner = MacSandbox(self.node, snapshot)
            runner.checks()
            # node:test runs when the file is executed directly, without the --test
            # coordinator spawning another process. Repository code cannot fork.
            result = runner.run(["test/word-count.test.mjs"])
            return {"status": "timeout" if result is None else "passed" if result == 0 else "failed"}

    def checks(self):
        with tempfile.TemporaryDirectory(prefix="capybara-check-") as temp:
            snapshot = Path(temp).resolve() / "project"
            self.snapshot(snapshot)
            return MacSandbox(self.node, snapshot).checks()


class MacSandbox:
    def __init__(self, node, snapshot):
        executable = shutil.which(node)
        if sys.platform != "darwin" or not Path("/usr/bin/sandbox-exec").is_file() or not executable:
            raise Denied("SANDBOX_UNAVAILABLE")
        self.node = Path(executable).resolve()
        self.snapshot = snapshot.resolve()
        # Node must be a real executable, not a shell shim relying on extra host access.
        with self.node.open("rb") as executable_file:
            if executable_file.read(4) not in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
                raise Denied("SANDBOX_UNAVAILABLE")
        quote = lambda x: json.dumps(str(x), ensure_ascii=False)
        self.profile = "\n".join([
            "(version 1)", "(deny default)",
            f"(allow process-exec (literal {quote(self.node)}))",
            "(allow sysctl-read)", "(allow mach-lookup)",
            "(allow file-read-metadata)",
            # dyld/V8 opens the filesystem root itself during startup. This permits
            # enumerating /, not reading its descendants or any user's file contents.
            '(allow file-read-data (literal "/"))',
            '(allow file-read* (subpath "/System") (subpath "/usr/lib") (subpath "/usr/share"))',
            f"(allow file-read* (literal {quote(self.node)}) (subpath {quote(self.snapshot)}))",
            '(allow file-read* file-write* (literal "/dev/null") (literal "/dev/random") (literal "/dev/urandom"))',
        ])

    def run(self, args, timeout=8):
        def limits():
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
            resource.setrlimit(resource.RLIMIT_CPU, (5, 5))
            resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024, 1024 * 1024))
            resource.setrlimit(resource.RLIMIT_NOFILE, (128, 128))
        process = subprocess.Popen(
            ["/usr/bin/sandbox-exec", "-p", self.profile, str(self.node),
             "--max-old-space-size=64", *args],
            cwd=self.snapshot, env={"PATH": "/usr/bin:/bin", "TMPDIR": str(self.snapshot)},
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True, preexec_fn=limits, close_fds=True)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            return None
        finally:
            # Also reap descendants after their parent exits, including on interruption.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()

    def checks(self):
        # Probe a listening local server so a connection error alone cannot fake egress denial.
        with tempfile.TemporaryDirectory(prefix="capybara-outside-") as temp, socket.socket() as server:
            outside = Path(temp).resolve() / "sentinel"
            outside.write_text("LAB_ONLY_CANARY_OUTSIDE")
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            script = r'''
const fs = require('node:fs');
const net = require('node:net');
const cp = require('node:child_process');
const fail = () => process.exit(1);
for (const key of ['HOME','GITHUB_TOKEN','CAPYBARA_GITHUB_TOKEN','CAPYBARA_API_KEY','OPENAI_API_KEY','NODE_OPTIONS'])
  if (process.env[key] !== undefined) fail();
let readDenied = false, writeDenied = false;
try { fs.readFileSync(process.argv[1]); } catch (e) { readDenied = ['EPERM','EACCES'].includes(e.code); }
try { fs.writeFileSync(process.argv[1], 'changed'); } catch (e) { writeDenied = ['EPERM','EACCES'].includes(e.code); }
if (!readDenied || !writeDenied) fail();
const child = cp.spawnSync('/bin/sh', ['-c', 'exit 0']);
if (!child.error || !['EPERM','EACCES'].includes(child.error.code)) fail();
const socket = net.connect({host:'127.0.0.1',port:Number(process.argv[2])});
socket.on('connect', fail);
socket.on('error', e => process.exit(['EPERM','EACCES'].includes(e.code) ? 0 : 1));
setTimeout(fail, 1500).unref();
'''
            result = self.run(["-e", script, str(outside), str(server.getsockname()[1])], timeout=4)
            if result != 0 or outside.read_text() != "LAB_ONLY_CANARY_OUTSIDE":
                raise Denied("SANDBOX_UNAVAILABLE")
        return list(CHECKS)


def rpc(workspace, request):
    if not isinstance(request, dict):
        raise Denied("INVALID_REQUEST")
    op = request.get("op")
    if op == "read":
        return workspace.read(request.get("path"))
    if op == "replace":
        workspace.replace(request.get("path"), request.get("expected"), request.get("next"))
        return None
    if op == "diff":
        return workspace.diff()
    if op == "test":
        return workspace.test()
    if op == "checks":
        return workspace.checks()
    raise Denied("INVALID_REQUEST")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "attach", "check", "test", "rpc", "rpc-lines"])
    parser.add_argument("--repo", required=True, help="Dedicated new lab directory or pristine starter clone")
    parser.add_argument("--node", default="node", help="Trusted Node executable, not a model argument")
    args = parser.parse_args()
    try:
        if args.command in ("init", "attach"):
            result = {"root": initialize(args.repo, attach=args.command == "attach")}
        else:
            workspace = Workspace(args.repo, args.node)
            if args.command in ("rpc", "rpc-lines"):
                raw = sys.stdin.buffer.read(65537)
                if len(raw) > 65536:
                    raise Denied("INVALID_REQUEST")
                if args.command == "rpc-lines":
                    try:
                        fields = [base64.b64decode(line, validate=True).decode("utf-8") for line in raw.splitlines()]
                        count = {"read": 2, "replace": 4, "diff": 1, "test": 1, "checks": 1}
                        if not fields or len(fields) != count.get(fields[0]):
                            raise Denied("INVALID_REQUEST")
                        request = {"op": fields[0]}
                        request.update(zip(("path", "expected", "next"), fields[1:]))
                    except (ValueError, UnicodeError):
                        raise Denied("INVALID_REQUEST") from None
                else:
                    request = json.loads(raw)
                with state_path(workspace.root).open("rb") as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    result = rpc(workspace, request)
            elif args.command == "check":
                result = workspace.checks()
            else:
                result = workspace.test()
        if args.command == "rpc-lines":
            if isinstance(result, dict):
                result = result["status"]
            elif isinstance(result, list):
                result = ",".join(result)
            print("OK\n" + base64.b64encode((result or "").encode()).decode())
        else:
            print(json.dumps({"ok": True, "result": result}))
    except Denied as error:
        print("ERROR\n" + str(error) if args.command == "rpc-lines" else json.dumps({"ok": False, "error": str(error)}))
        return 0 if args.command.startswith("rpc") else 1
    except Exception:
        print("ERROR\nWORKSHOP_BACKEND_FAILED" if args.command == "rpc-lines" else json.dumps({"ok": False, "error": "WORKSHOP_BACKEND_FAILED"}))
        return 0 if args.command.startswith("rpc") else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
