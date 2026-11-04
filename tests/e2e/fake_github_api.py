#!/usr/bin/env python3
"""GitHub REST double for the panel: as strict as GitHub about drafts, SHAs and conflicts."""
import json, os, re, subprocess, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port, state, remote = int(sys.argv[1]), sys.argv[2], sys.argv[3]


def git(*args):
    return subprocess.run(["git", "-C", remote, *args], capture_output=True, text=True)


def pull(number):
    return json.load(open(state))["pulls"].get(number)


def head(pr):
    return git("rev-parse", "refs/heads/" + pr["head"]).stdout.strip()


def merged_tree(sha):
    result = git("merge-tree", "--write-tree", "refs/heads/main", sha)
    return result.stdout.splitlines()[0] if result.returncode == 0 else None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?")[0]
        if not self.headers.get("Authorization", "").startswith("Bearer "):
            return self.reply(401, {"message": "Bad credentials"})
        if re.fullmatch(r"/app/installations/\d+", path):
            return self.reply(200, {"id": int(path.rsplit("/", 1)[1])})
        if path == "/repos/e2e/demo":
            return self.reply(200, {"permissions": {"push": True, "pull": True}})
        if path == "/repos/e2e/demo/pulls":
            return self.reply(200, [])
        if m := re.fullmatch(r"/repos/e2e/demo/pulls/(\d+)", path):
            pr = pull(m[1])
            if not pr:
                return self.reply(404, {})
            sha = head(pr)
            return self.reply(200, {
                "state": "closed" if pr.get("merged") else "open", "merged": bool(pr.get("merged")),
                "draft": bool(pr.get("draft")), "head": {"sha": sha},
                "mergeable": merged_tree(sha) is not None})
        if re.fullmatch(r"/repos/e2e/demo/pulls/\d+/reviews", path):
            return self.reply(200, [])
        if re.fullmatch(r"/repos/e2e/demo/commits/\w+/check-runs", path):
            runs = [{"name": "AgentFarm " + k, "status": "completed", "conclusion": "success"} for k in ("unit", "integration")]
            return self.reply(200, {"total_count": len(runs), "check_runs": runs})
        if re.fullmatch(r"/repos/e2e/demo/commits/\w+/status", path):
            return self.reply(200, {"total_count": 0, "state": "pending"})
        if m := re.fullmatch(r"/repos/e2e/demo/compare/(\w+)\.\.\.(\w+)", path):
            names = {"A": "added", "M": "modified", "D": "removed"}
            lines = git("diff", "--name-status", m[1], m[2]).stdout.splitlines()
            files = [{"filename": l.split("\t")[1], "status": names.get(l[0], "modified"), "patch": ""} for l in lines]
            return self.reply(200, {"files": files})
        self.reply(404, {"message": path})

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
        if re.fullmatch(r"/app-manifests/\w+/conversions", self.path):
            # GitHub answers this one without credentials: the code is the secret.
            return self.reply(201, {"id": 4242, "slug": "agentfarm-e2e", "pem": open(os.environ["E2E_APP_PEM"]).read()})
        if not self.headers.get("Authorization", "").startswith("Bearer "):
            return self.reply(401, {"message": "Bad credentials"})
        if re.fullmatch(r"/app/installations/\d+/access_tokens", self.path):
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}")
            if body.get("repositories"):
                return self.reply(201, {"token": "ghs_e2e_repo_" + "_".join(body["repositories"]), "expires_at": "2099-01-01T00:00:00Z", "repository_selection": "selected"})
            return self.reply(201, {"token": "ghs_e2e", "expires_at": "2099-01-01T00:00:00Z", "repository_selection": "all"})
        self.reply(404, {"message": self.path})

    def do_PUT(self):
        m = re.fullmatch(r"/repos/e2e/demo/pulls/(\d+)/merge", self.path)
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}")
        pr = pull(m[1]) if m else None
        if not pr:
            return self.reply(404, {})
        sha = head(pr)
        if body.get("sha") != sha:
            return self.reply(409, {"message": "Head branch was modified"})
        tree = merged_tree(sha)
        if pr.get("draft") or tree is None:
            return self.reply(405, {"message": "Pull Request is not mergeable"})
        env = ["-c", "user.name=github", "-c", "user.email=github@localhost"]
        commit = git(*env, "commit-tree", tree, "-p", "refs/heads/main", "-m", pr["title"]).stdout.strip()
        git("update-ref", "refs/heads/main", commit)
        data = json.load(open(state))
        data["pulls"][m[1]]["merged"] = True
        json.dump(data, open(state, "w"))
        self.reply(200, {"merged": True, "sha": commit})


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
