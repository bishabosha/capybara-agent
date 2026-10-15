#!/usr/bin/env python3
"""Trusted, repository-scoped GitHub backend. Repository code never handles credentials."""
import argparse
import base64
import difflib
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PROFILES = ROOT / ".agent-runtime/github"
LIMIT = 65536
TOTAL = 1048576
API_LIMIT = 8388608
ERRORS = frozenset("NOT_CONFIGURED INVALID_REQUEST POLICY_DENIED PATH_DENIED WRITE_DENIED "
    "CONTENT_DENIED CONTENT_CHANGED SESSION_EXISTS SESSION_REQUIRED BASE_CHANGED "
    "BRANCH_CHANGED NO_CHANGES TESTS_REQUIRED TESTS_NOT_CONFIGURED SANDBOX_UNAVAILABLE "
    "PUSH_REQUIRED AUTH_FAILED NOT_FOUND RATE_LIMITED GITHUB_UNAVAILABLE INVALID_RESPONSE "
    "GITHUB_REQUEST_REJECTED BACKEND_FAILED".split())
SECRET = re.compile(r"LAB_ONLY_CANARY_|github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]{16,}|"
                    r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bsk-[A-Za-z0-9_-]{16,}|"
                    r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")
SHA = re.compile(r"[0-9a-f]{40}\Z")


class Denied(Exception):
    """Only fixed, non-secret error codes cross the tool boundary."""


def require(condition, code="INVALID_REQUEST"):
    if not condition:
        raise Denied(code)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def path_name(value):
    require(isinstance(value, str) and len(value) < 240, "PATH_DENIED")
    parts = value.split("/")
    require(all(re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", p) for p in parts), "PATH_DENIED")
    require(not any(p.lower() in {"credentials", "secrets", "id_rsa", "id_ed25519"}
                    or p.lower().endswith((".pem", ".key", ".p12", ".pfx")) for p in parts), "PATH_DENIED")
    return value


def sha(value):
    require(isinstance(value, str) and SHA.fullmatch(value), "INVALID_RESPONSE")
    return value


class Guard:
    def __init__(self, token):
        require(isinstance(token, str) and 16 <= len(token) <= 4096 and token.isascii()
                and not any(c.isspace() for c in token), "AUTH_FAILED")
        raw = token.encode()
        self.denied = {token, urllib.parse.quote(token, safe=""), raw.hex(),
                       base64.b64encode(raw).decode(),
                       base64.b64encode(b"x-access-token:" + raw).decode()}

    def text(self, value, limit=LIMIT):
        require(isinstance(value, str) and len(value.encode()) <= limit, "CONTENT_DENIED")
        require(not SECRET.search(value) and not any(s in value for s in self.denied)
                and not any(ord(c) < 32 and c not in "\n\r\t" for c in value), "CONTENT_DENIED")
        return value

    def payload(self, value):
        # Scan the entire serialized request before any network mutation, including metadata.
        self.text(json.dumps(value, ensure_ascii=False), TOTAL * 2)
        return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Denied("GITHUB_REQUEST_REJECTED")


class Api:
    """Fixed origin, fixed repository, no proxies, no redirect or server-selected URL following."""
    def __init__(self, repo, token):
        self.prefix = "/repos/" + repo
        self.token = token
        self.guard = Guard(token)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, suffix="", body=None):
        require(method in {"GET", "POST", "PATCH"})
        require(isinstance(suffix, str) and (not suffix or suffix.startswith("/"))
                and not any(c in suffix for c in "\r\n#"))
        if body is not None:
            self.guard.payload(body)
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request("https://api.github.com" + self.prefix + suffix, data=data,
            method=method, headers={"Accept": "application/vnd.github+json",
                "Authorization": "Bearer " + self.token, "Content-Type": "application/json",
                "X-GitHub-Api-Version": "2026-03-10", "User-Agent": "capybara-github-skill"})
        try:
            with self.opener.open(req, timeout=20) as response:
                raw = response.read(API_LIMIT + 1)
            require(len(raw) <= API_LIMIT, "INVALID_RESPONSE")
            return json.loads(raw)
        except urllib.error.HTTPError as error:
            # Never return an error body, URL, header or original exception string.
            code = {401: "AUTH_FAILED", 403: "GITHUB_REQUEST_REJECTED", 404: "NOT_FOUND",
                    409: "GITHUB_REQUEST_REJECTED", 422: "GITHUB_REQUEST_REJECTED",
                    429: "RATE_LIMITED"}.get(error.code, "GITHUB_UNAVAILABLE")
            try:
                error.close()
            except Exception:
                pass
            raise Denied(code) from None
        except (OSError, ValueError):
            raise Denied("GITHUB_UNAVAILABLE") from None


def validate_config(config):
    require(isinstance(config, dict) and config.get("version") == 1, "NOT_CONFIGURED")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*",
                         config.get("repository", "")), "POLICY_DENIED")
    base = config.get("base", "")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./-]*", base) and ".." not in base
            and "//" not in base and not base.endswith(("/", ".", ".lock")), "POLICY_DENIED")
    reads, writes = config.get("read"), config.get("write")
    require(isinstance(reads, list) and isinstance(writes, list) and 0 < len(reads) <= 64
            and len(set(reads)) == len(reads) and len(set(writes)) == len(writes)
            and bool(writes) and set(writes) <= set(reads), "POLICY_DENIED")
    for value in reads:
        path_name(value)
    require(len({p.lower() for p in reads}) == len(reads), "POLICY_DENIED")
    require(type(config.get("allow_untested_draft")) is bool, "POLICY_DENIED")
    test = config.get("test")
    if test is not None:
        require(isinstance(test, dict) and test.get("kind") == "node"
                and test.get("entry") in reads and isinstance(test.get("executable"), str)
                and Path(test["executable"]).is_absolute(), "POLICY_DENIED")
        path_name(test["entry"])
    return config


def private_json(path):
    require(not path.is_symlink(), "POLICY_DENIED")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "r") as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
                and info.st_mode & 0o077 == 0 and info.st_size <= TOTAL * 4, "POLICY_DENIED")
        return json.load(stream)


def save_json(path, value):
    # A separate persistent lock file serializes RPCs across atomic state replacements.
    fd, name = tempfile.mkstemp(prefix=".github-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as out:
            json.dump(value, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


class Workflow:
    def __init__(self, profile, token, api_factory=Api):
        self.profile = Path(profile).absolute()
        self.config = validate_config(private_json(self.profile))
        self.guard = Guard(token)
        self.api = api_factory(self.config["repository"], token)
        self.state_path = self.profile.with_suffix(".state.json")
        self.state = private_json(self.state_path) if self.state_path.exists() else None
        if self.state is not None:
            require(self.state["policy"] == digest(self.config), "POLICY_DENIED")

    def save(self):
        save_json(self.state_path, self.state)

    def active(self):
        require(self.state is not None, "SESSION_REQUIRED")
        return self.state

    def issue(self, number):
        require(type(number) is int and 0 < number < 2147483647)
        item = self.api.request("GET", f"/issues/{number}")
        require(isinstance(item, dict) and item.get("number") == number
                and "pull_request" not in item, "INVALID_RESPONSE")
        return [str(number), self.guard.text(item["title"], 4096),
                self.guard.text(item.get("body") or ""),
                f'https://github.com/{self.config["repository"]}/issues/{number}']

    def issues(self, page):
        require(type(page) is int and 1 <= page <= 1000)
        items = self.api.request("GET", f"/issues?state=open&per_page=25&page={page}")
        require(isinstance(items, list) and len(items) <= 25, "INVALID_RESPONSE")
        result = []
        for item in items:
            require(isinstance(item, dict), "INVALID_RESPONSE")
            if "pull_request" in item:
                continue
            number = item.get("number")
            require(type(number) is int and 0 < number < 2147483647, "INVALID_RESPONSE")
            result.extend([str(number), self.guard.text(item["title"], 4096),
                           f'https://github.com/{self.config["repository"]}/issues/{number}'])
        return result

    def base_head(self):
        base = urllib.parse.quote(self.config["base"], safe="/")
        return sha(self.api.request("GET", "/git/ref/heads/" + base)["object"]["sha"])

    def start(self, number):
        if self.state is not None:
            require(self.state["issue"] == number, "SESSION_EXISTS")
            return self.workspace()
        self.issue(number)  # Validate/scans issue before creating a workspace.
        head = self.base_head()
        tree = sha(self.api.request("GET", "/git/commits/" + head)["tree"]["sha"])
        listing = self.api.request("GET", "/git/trees/" + tree + "?recursive=1")
        require(listing.get("truncated") is False and isinstance(listing.get("tree"), list), "INVALID_RESPONSE")
        entries = {item["path"]: item for item in listing["tree"]}
        files, modes = {}, {}
        for path in self.config["read"]:
            item = entries.get(path)
            if item is None:
                require(path in self.config["write"], "PATH_DENIED")
                continue
            require(item.get("type") == "blob" and item.get("mode") in {"100644", "100755"}
                    and type(item.get("size")) is int and item["size"] <= LIMIT, "PATH_DENIED")
            for parent in Path(path).parents:
                if str(parent) != ".":
                    require(entries.get(parent.as_posix(), {}).get("type") == "tree", "PATH_DENIED")
            blob = self.api.request("GET", "/git/blobs/" + sha(item["sha"]))
            require(blob.get("encoding") == "base64" and isinstance(blob.get("content"), str), "INVALID_RESPONSE")
            data = base64.b64decode("".join(blob["content"].split()), validate=True).decode("utf-8")
            raw = data.encode()
            require(hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == item["sha"],
                    "INVALID_RESPONSE")
            files[path] = self.guard.text(data)
            modes[path] = item["mode"]
        require(sum(len(x.encode()) for x in files.values()) <= TOTAL, "CONTENT_DENIED")
        self.state = {"policy": digest(self.config), "issue": number, "base": head, "tree": tree,
            "branch": f"capybara/issue-{number}-{secrets.token_hex(6)}", "baseline": files,
            "files": dict(files), "modes": modes, "tested": None, "published": None,
            "published_digest": None, "pending": None, "pr": None}
        self.save()
        return self.workspace()

    def workspace(self):
        state = self.active()
        return [str(state["issue"]), state["branch"], state["base"]]

    def files(self):
        self.active()
        return list(self.config["read"])

    def read(self, path):
        require(path in self.config["read"], "PATH_DENIED")
        return self.guard.text(self.active()["files"].get(path, ""))

    def replace(self, path, expected, next_value):
        require(path in self.config["write"], "WRITE_DENIED")
        self.guard.text(expected)
        self.guard.text(next_value)
        require(self.read(path) == expected, "CONTENT_CHANGED")
        state = self.active()
        proposed = dict(state["files"], **{path: next_value})
        require(sum(len(x.encode()) for x in proposed.values()) <= TOTAL, "CONTENT_DENIED")
        state["files"] = proposed
        state["tested"] = None
        state["pending"] = None
        self.save()

    def changes(self):
        state = self.active()
        for path in self.config["write"]:
            if state["baseline"].get(path) != state["files"].get(path):
                yield path, self.guard.text(state["files"][path])

    def diff(self):
        state = self.active()
        result = []
        for path, after in self.changes():
            before = self.guard.text(state["baseline"].get(path, ""))
            result.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                          fromfile="a/" + path, tofile="b/" + path))
        return self.guard.text("".join(result), TOTAL)

    def test(self):
        state = self.active()
        config = self.config.get("test")
        require(config is not None, "TESTS_NOT_CONFIGURED")
        # Reuse the tested default-deny process boundary, not the workshop's fixed file API.
        spec = importlib.util.spec_from_file_location("capybara_sandbox", ROOT / "scripts/workshop.py")
        sandbox = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(sandbox)
        tested = digest(state["files"])
        with tempfile.TemporaryDirectory(prefix="capybara-github-test-") as temp:
            snapshot = Path(temp).resolve() / "snapshot"
            snapshot.mkdir()
            for path, data in state["files"].items():
                target = snapshot / path_name(path)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(self.guard.text(data))
            try:
                runner = sandbox.MacSandbox(config["executable"], snapshot)
                runner.checks()
                outcome = runner.run([config["entry"]])
            except sandbox.Denied:
                raise Denied("SANDBOX_UNAVAILABLE") from None
        status = "timeout" if outcome is None else "passed" if outcome == 0 else "failed"
        state["tested"] = tested if status == "passed" else None
        self.save()
        return [status]

    def branch_head(self):
        state = self.active()
        try:
            return sha(self.api.request("GET", "/git/ref/heads/" + state["branch"])["object"]["sha"])
        except Denied as error:
            if str(error) == "NOT_FOUND":
                return None
            raise

    def push(self, message):
        self.guard.text(message, 4096)
        require(bool(message.strip()))
        state = self.active()
        fingerprint = digest(state["files"])
        changes = list(self.changes())
        require(bool(changes), "NO_CHANGES")
        if not self.config["allow_untested_draft"]:
            require(state["tested"] == fingerprint, "TESTS_REQUIRED")
        # All content and metadata are checked before the first remote mutation.
        tree_body = self.guard.payload({"base_tree": state["tree"], "tree": [
            {"path": path, "mode": state["modes"].get(path, "100644"), "type": "blob", "content": data}
            for path, data in changes]})
        require(self.base_head() == state["base"], "BASE_CHANGED")
        current = self.branch_head()
        pending = state["pending"]
        if pending and pending["digest"] == fingerprint and current == pending["commit"]:
            state["published"], state["published_digest"] = current, fingerprint
            state["pending"] = None
            self.save()
            return [state["branch"], current]
        require(current == state["published"], "BRANCH_CHANGED")
        if state["published_digest"] == fingerprint:
            return [state["branch"], state["published"]]
        if pending is None or pending["digest"] != fingerprint:
            tree = sha(self.api.request("POST", "/git/trees", tree_body)["sha"])
            commit = sha(self.api.request("POST", "/git/commits", {
                "message": message, "tree": tree, "parents": [state["published"] or state["base"]]})["sha"])
            state["pending"] = {"commit": commit, "digest": fingerprint}
            self.save()
        commit = state["pending"]["commit"]
        if state["published"] is None:
            self.api.request("POST", "/git/refs", {"ref": "refs/heads/" + state["branch"], "sha": commit})
        else:
            self.api.request("PATCH", "/git/refs/heads/" + state["branch"], {"sha": commit, "force": False})
        require(self.branch_head() == commit, "BRANCH_CHANGED")
        state["published"], state["published_digest"], state["pending"] = commit, fingerprint, None
        self.save()
        return [state["branch"], commit]

    def published(self):
        state = self.active()
        require(state["published"] is not None and state["published_digest"] == digest(state["files"]), "PUSH_REQUIRED")
        require(self.branch_head() == state["published"], "BRANCH_CHANGED")
        return state

    def pull_request(self, title, body):
        self.guard.text(title, 256)
        self.guard.text(body)
        require(bool(title.strip()))
        state = self.published()
        if state["pr"]:
            return self.pr_result(state["pr"])
        # Resolve an ambiguous prior POST by exact head/base rather than creating duplicates.
        head = self.config["repository"].split("/")[0] + ":" + state["branch"]
        query = urllib.parse.urlencode({"state": "open", "head": head, "base": self.config["base"], "per_page": 100})
        existing = self.api.request("GET", "/pulls?" + query)
        require(isinstance(existing, list), "INVALID_RESPONSE")
        candidates = [x for x in existing if x.get("head", {}).get("ref") == state["branch"]
                      and x.get("head", {}).get("sha") == state["published"]
                      and x.get("base", {}).get("ref") == self.config["base"]
                      and x.get("head", {}).get("repo", {}).get("full_name", "").lower()
                      == self.config["repository"].lower()]
        require(len(candidates) <= 1, "INVALID_RESPONSE")
        if candidates:
            require(candidates[0].get("draft") is True, "POLICY_DENIED")
            number = candidates[0]["number"]
        else:
            payload = self.guard.payload({"title": title, "body": body, "head": state["branch"],
                "base": self.config["base"], "draft": True, "maintainer_can_modify": False})
            number = self.api.request("POST", "/pulls", payload)["number"]
        require(type(number) is int and number > 0, "INVALID_RESPONSE")
        state["pr"] = number
        self.save()
        return self.pr_result(number)

    def pr_result(self, number):
        return [str(number), f'https://github.com/{self.config["repository"]}/pull/{number}']

    def checks(self):
        state = self.published()
        commit = state["published"]
        statuses = self.api.request("GET", f"/commits/{commit}/status?per_page=100")
        checks = self.api.request("GET", f"/commits/{commit}/check-runs?per_page=100")
        runs = checks.get("check_runs", [])
        require(isinstance(runs, list) and type(checks.get("total_count")) is int, "INVALID_RESPONSE")
        require(checks["total_count"] <= len(runs), "INVALID_RESPONSE")
        require(type(statuses.get("total_count")) is int and statuses["total_count"] <= 100, "INVALID_RESPONSE")
        present = bool(runs) or statuses["total_count"] > 0
        failed = statuses.get("state") in {"failure", "error"} or any(
            r.get("conclusion") in {"failure", "cancelled", "timed_out", "action_required", "startup_failure"}
            for r in runs)
        passed = present and (statuses["total_count"] == 0 or statuses.get("state") == "success") and all(
            r.get("head_sha") == commit and r.get("status") == "completed" and r.get("conclusion") == "success"
            for r in runs)
        return [commit, "failed" if failed else "passed" if passed else "pending"]


def dispatch(workflow, fields):
    require(bool(fields))
    op, args = fields[0], fields[1:]
    counts = {"issues": 1, "issue": 1, "start": 1, "files": 0, "read": 1, "replace": 3, "diff": 0,
              "test": 0, "push": 1, "pr": 2, "checks": 0}
    require(op in counts and len(args) == counts[op])
    if op in {"issues", "issue", "start"}:
        require(args[0].isdigit() and len(args[0]) <= 10)
        return getattr(workflow, op)(int(args[0]))
    if op in {"read", "diff"}:
        return [getattr(workflow, op)(*args)]
    if op == "replace":
        workflow.replace(*args)
        return []
    if op == "pr":
        return workflow.pull_request(*args)
    return getattr(workflow, op)(*args)


def main(api_factory=Api):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init", help="Create a host-owned repository policy; performs no network requests")
    init.add_argument("--name", required=True)
    init.add_argument("--repository", required=True, help="owner/repository, no URL or embedded token")
    init.add_argument("--base", default="main")
    init.add_argument("--read", action="append", required=True, dest="reads")
    init.add_argument("--write", action="append", required=True, dest="writes")
    init.add_argument("--test-entry")
    init.add_argument("--node", help="Absolute trusted native Node executable")
    init.add_argument("--allow-untested-draft", action="store_true")
    rpc = sub.add_parser("rpc-lines")
    rpc.add_argument("--profile", required=True)
    args = parser.parse_args()
    try:
        if args.command == "init":
            require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.name))
            config = validate_config({"version": 1, "repository": args.repository, "base": args.base,
                "read": args.reads, "write": args.writes, "allow_untested_draft": args.allow_untested_draft,
                "test": {"kind": "node", "entry": args.test_entry, "executable": args.node}
                        if args.test_entry else None})
            PROFILES.mkdir(parents=True, mode=0o700, exist_ok=True)
            target = PROFILES / (args.name + ".json")
            require(not target.exists(), "POLICY_DENIED")
            save_json(target, config)
            print(str(target))
            return 0
        token = os.environ.get("CAPYBARA_GITHUB_TOKEN", "")
        raw = sys.stdin.buffer.read(TOTAL * 2 + 1)
        require(len(raw) <= TOTAL * 2)
        fields = [base64.b64decode(x, validate=True).decode("utf-8") for x in raw.splitlines()]
        profile = Path(args.profile).absolute()
        # State and policy stay outside repository snapshots. No repo code is imported here.
        lock_path = profile.with_suffix(".lock")
        fd = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
        with os.fdopen(fd, "rb") as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
                    and info.st_mode & 0o077 == 0, "POLICY_DENIED")
            fcntl.flock(lock, fcntl.LOCK_EX)
            workflow = Workflow(profile, token, api_factory)
            result = dispatch(workflow, fields)
            require(isinstance(result, list), "INVALID_RESPONSE")
            for item in result:
                workflow.guard.text(item, TOTAL)
            print("OK")
            for item in result:
                print(base64.b64encode(item.encode()).decode())
        return 0
    except Denied as error:
        code = str(error) if str(error) in ERRORS else "BACKEND_FAILED"
    except Exception:
        code = "BACKEND_FAILED"
    if args.command == "rpc-lines":
        print("ERROR\n" + code)
        return 0
    print(code, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
