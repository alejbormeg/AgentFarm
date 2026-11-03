#!/usr/bin/env python3
"""GitHub CLI double: clones a local bare repository and keeps issues/PRs in a JSON file."""
import json, os, subprocess, sys

STATE = os.environ["E2E_GH_STATE"]
REMOTE = os.environ["E2E_REMOTE"]


def load():
    return json.load(open(STATE)) if os.path.exists(STATE) else {"issues": {}, "pulls": {}}


def save(s):
    json.dump(s, open(STATE, "w"))


args = sys.argv[1:]
if args[:2] == ["repo", "clone"]:
    sys.exit(subprocess.call(["git", "clone", "-q", REMOTE, args[3]]))
if args[:2] == ["pr", "ready"]:
    s = load()
    s["pulls"][args[2]]["draft"] = "--undo" in args
    save(s)
    sys.exit(0)
if args[0] != "api":
    sys.exit("unsupported: " + " ".join(args))
method = args[args.index("--method") + 1]
endpoint = args[args.index("--method") + 2]
body = json.load(open(args[args.index("--input") + 1])) if "--input" in args else {}
s = load()
path = endpoint.split("?")[0].split("/")[3:]  # after repos/owner/name
out = {}
if not path:
    out = {"default_branch": "main"}
elif path[0] == "issues" and len(path) == 1:
    if method == "POST":
        n = len(s["issues"]) + 1
        s["issues"][str(n)] = body
        out = {"number": n}
    else:
        out = [dict(v, number=int(k)) for k, v in s["issues"].items()]
elif path[0] == "issues":
    s["issues"].setdefault(path[1], {}).update(body)
elif path[0] == "pulls" and len(path) == 1:
    if method == "POST":
        n = len(s["pulls"]) + 1
        s["pulls"][str(n)] = body
        out = {"number": n}
    else:
        head = endpoint.split("head=")[1].split(":", 1)[1] if "head=" in endpoint else None
        out = [dict(v, number=int(k)) for k, v in s["pulls"].items() if v.get("head") == head]
elif path[0] == "pulls":
    s["pulls"].setdefault(path[1], {}).update(body)
save(s)
print(json.dumps(out))
