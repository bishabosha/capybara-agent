"""Deterministic GitHub transport for tests only; never selected by production wiring."""
import base64
import copy
import hashlib
import json
from pathlib import Path
import sys
import urllib.parse

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import github_workflow as gh

TOKEN = "SYNTHETIC_HOST_TOKEN_abcdefghijklmnopqrstuvwxyz"


def object_sha(kind, value):
    data = value.encode() if isinstance(value, str) else json.dumps(value, sort_keys=True).encode()
    return hashlib.sha1(kind.encode() + b" " + str(len(data)).encode() + b"\0" + data).hexdigest()


def seed():
    starter = ROOT / "presentations/capybara-architecture/workshop/project"
    files = {p: (starter / p).read_text() for p in
             ["README.md", "src/word-count.mjs", "test/word-count.test.mjs"]}
    entries = [{"path": "src", "type": "tree", "mode": "040000", "sha": "1" * 40},
               {"path": "test", "type": "tree", "mode": "040000", "sha": "2" * 40}]
    blobs = {}
    for path, data in files.items():
        key = object_sha("blob", data)
        blobs[key] = data
        entries.append({"path": path, "type": "blob", "mode": "100644", "sha": key, "size": len(data.encode())})
    tree = object_sha("tree", entries)
    commit = object_sha("commit", tree)
    return {"refs": {"main": commit}, "commits": {commit: {"tree": {"sha": tree}}},
            "trees": {tree: entries}, "blobs": blobs, "requests": [], "pulls": [],
            "issues": {"1": {"number": 1, "title": "Handle whitespace and empty input", "body": "Fix countWords."}},
            "check_status": "pending"}


class FakeApi:
    def __init__(self, repo, token, store=None):
        self.repo = repo
        self.guard = gh.Guard(token)
        self.store = Path(store) if store else None
        self.data = json.loads(self.store.read_text()) if self.store and self.store.exists() else seed()

    def save(self):
        if self.store:
            self.store.write_text(json.dumps(self.data))

    def request(self, method, suffix="", body=None):
        if body is not None:
            self.guard.payload(body)
        self.data["requests"].append([method, suffix, copy.deepcopy(body)])
        self.save()
        d = self.data
        path = urllib.parse.urlsplit(suffix).path
        if method == "GET":
            if path == "/issues":
                return copy.deepcopy(list(d["issues"].values()))
            if path.startswith("/issues/"):
                result = d["issues"].get(path.split("/")[-1])
                if result is None:
                    raise gh.Denied("NOT_FOUND")
                return copy.deepcopy(result)
            if path.startswith("/git/ref/heads/"):
                key = path.removeprefix("/git/ref/heads/")
                if key not in d["refs"]:
                    raise gh.Denied("NOT_FOUND")
                return {"object": {"sha": d["refs"][key]}}
            if path.startswith("/git/commits/"):
                return copy.deepcopy(d["commits"][path.split("/")[-1]])
            if path.startswith("/git/trees/"):
                return {"truncated": False, "tree": copy.deepcopy(d["trees"][path.split("/")[-1]])}
            if path.startswith("/git/blobs/"):
                data = d["blobs"][path.split("/")[-1]]
                return {"encoding": "base64", "content": base64.b64encode(data.encode()).decode()}
            if path == "/pulls":
                return copy.deepcopy(d["pulls"])
            if path.endswith("/status"):
                return {"state": "pending", "total_count": 0}
            if path.endswith("/check-runs"):
                commit = path.split("/")[2]
                status = d["check_status"]
                runs = [] if status == "pending" else [{"head_sha": commit, "status": "completed",
                    "conclusion": "success" if status == "passed" else "failure"}]
                return {"total_count": len(runs), "check_runs": runs}
        if method == "POST" and path == "/git/trees":
            entries = {x["path"]: x for x in copy.deepcopy(d["trees"][body["base_tree"]])}
            for item in body["tree"]:
                data = item["content"]
                key = object_sha("blob", data)
                d["blobs"][key] = data
                entries[item["path"]] = {"path": item["path"], "mode": item["mode"], "type": "blob",
                                         "sha": key, "size": len(data.encode())}
            entries = list(entries.values())
            key = object_sha("tree", entries)
            d["trees"][key] = entries
            result = {"sha": key}
        elif method == "POST" and path == "/git/commits":
            key = object_sha("commit", body)
            d["commits"][key] = {"tree": {"sha": body["tree"]}, "parents": body["parents"]}
            result = {"sha": key}
        elif method == "POST" and path == "/git/refs":
            key = body["ref"].removeprefix("refs/heads/")
            gh.require(key not in d["refs"], "GITHUB_REQUEST_REJECTED")
            d["refs"][key] = body["sha"]
            result = {"object": {"sha": body["sha"]}}
        elif method == "PATCH" and path.startswith("/git/refs/heads/"):
            key = path.removeprefix("/git/refs/heads/")
            gh.require(body["force"] is False and d["refs"][key] in d["commits"][body["sha"]]["parents"],
                       "GITHUB_REQUEST_REJECTED")
            d["refs"][key] = body["sha"]
            result = {"object": {"sha": body["sha"]}}
        elif method == "POST" and path == "/pulls":
            result = {**body, "number": len(d["pulls"]) + 2,
                "head": {"ref": body["head"], "sha": d["refs"][body["head"]], "repo": {"full_name": self.repo}},
                "base": {"ref": body["base"]}}
            d["pulls"].append(result)
        else:
            raise AssertionError((method, suffix))
        self.save()
        if d.pop("drop_after_mutation", False):
            self.save()
            raise gh.Denied("GITHUB_UNAVAILABLE")
        return copy.deepcopy(result)


if __name__ == "__main__":
    # State is next to a test-owned profile, never read from a model argument.
    profile = Path(sys.argv[sys.argv.index("--profile") + 1])
    sys.exit(gh.main(api_factory=lambda repo, token: FakeApi(repo, token, profile.with_suffix(".remote.json"))))
