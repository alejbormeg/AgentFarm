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



# What the App was created with (GitHubAppSetup.PERMISSIONS).
APP_PERMISSIONS = {"metadata": "read", "checks": "write", "vulnerability_alerts": "read", "statuses": "read",
                   "contents": "write", "pull_requests": "write", "actions": "read", "issues": "write", "workflows": "write"}

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
        # Skill catalogues the owner can browse from the panel.
        if path == "/repos/e2e/skills/contents/skills":
            return self.reply(200, [{"name": "casa", "type": "dir", "path": "skills/casa"}])
        if path == "/repos/e2e/skills/contents/skills/casa/SKILL.md":
            import base64
            text = "# Casa\nUsa nombres claros en los commits.\n"
            return self.reply(200, {"type": "file", "size": len(text), "encoding": "base64", "sha": "0123456789abcdef", "html_url": "https://github.com/e2e/skills", "content": base64.b64encode(text.encode()).decode()})
        # Open issues of the demo repository, for the owner to choose from the panel.
        if path == "/repos/e2e/demo/issues" and "state=open" in self.path:
            if "page=2" in self.path:
                return self.reply(200, [])
            return self.reply(200, [
                {"number": 31, "title": "Falla el botón", "body": "El botón de guardar no hace nada.", "state": "open", "html_url": "https://github.com/e2e/demo/issues/31", "user": {"login": "ana"}, "author_association": "OWNER", "labels": [{"name": "bug"}], "comments": 1, "created_at": "2026-11-01T10:00:00Z"},
                {"number": 32, "title": "Idea de alguien", "body": None, "state": "open", "html_url": "https://github.com/e2e/demo/issues/32", "user": {"login": "x"}, "author_association": "NONE", "labels": [], "comments": 0, "created_at": "2026-11-02T10:00:00Z"},
                {"number": 33, "title": "Una PR", "pull_request": {}, "body": "x", "state": "open", "html_url": "u", "user": {"login": "b"}, "labels": []},
                {"number": 34, "title": "Adoptar e2e/demo", "body": "<!-- agentfarm:x -->", "state": "open", "html_url": "u", "user": {"login": "bot"}, "labels": []}])
        if path in ("/repos/e2e/demo/issues/31", "/repos/e2e/demo/issues/32"):
            n = int(path.rsplit("/", 1)[1])
            return self.reply(200, {"number": n, "title": "Falla el botón" if n == 31 else "Idea de alguien", "body": "El botón de guardar no hace nada." if n == 31 else None,
                                    "state": "open", "html_url": f"https://github.com/e2e/demo/issues/{n}", "user": {"login": "ana" if n == 31 else "x"}, "author_association": "OWNER" if n == 31 else "NONE"})
        if path == "/repos/e2e/skills":
            return self.reply(200, {"default_branch": "main", "stargazers_count": 7})
        if path == "/repos/e2e/skills/git/trees/main":
            return self.reply(200, {"truncated": False, "tree": [
                {"path": "skills/casa/SKILL.md", "type": "blob", "sha": "0123456789abcdef"},
                {"path": "README.md", "type": "blob", "sha": "r"}]})
        if path == "/search/repositories":
            return self.reply(200, {"items": [{"full_name": "e2e/skills", "description": "Skills de prueba", "stargazers_count": 7}]})
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
        if re.fullmatch(r"/repos/e2e/demo/commits/\w+/check-runs", path) and os.path.exists(state + ".no-ci"):
            return self.reply(200, {"total_count": 0, "check_runs": []})
        if m := re.fullmatch(r"/repos/e2e/demo/commits/(\w+)/check-runs", path):
            # Like GitHub: for the commit asked about, run by GitHub Actions.
            runs = [{"name": "AgentFarm " + k, "status": "completed", "conclusion": "success", "head_sha": m.group(1),
                     "app": {"id": 15368, "slug": "github-actions"}} for k in ("unit", "integration")]
            # And whatever the panel published for that commit with its App (local CI).
            if os.path.exists(state + ".checks"):
                runs += [r for r in json.load(open(state + ".checks")) if r["head_sha"] == m.group(1)]
            return self.reply(200, {"total_count": len(runs), "check_runs": runs})
        if re.fullmatch(r"/repos/e2e/demo/commits/\w+/status", path):
            return self.reply(200, {"total_count": 0, "state": "pending"})
        if m := re.fullmatch(r"/repos/e2e/demo/compare/(\w+)\.\.\.(\w+)", path):
            names = {"A": "added", "M": "modified", "D": "removed"}
            lines = git("diff", "--no-renames", "--name-status", m[1], m[2]).stdout.splitlines()
            files = []
            for l in lines:
                name = l.split("\t")[1]
                # Like GitHub: the hunks without the file headers, and how many lines changed.
                patch = git("diff", "--no-renames", m[1], m[2], "--", name).stdout
                patch = patch[patch.find("@@"):] if "@@" in patch else ""
                changes = sum(1 for x in patch.splitlines() if x[:1] in "+-")
                files.append({"filename": name, "status": names.get(l[0], "modified"), "patch": patch, "changes": changes})
            return self.reply(200, {"files": files})
        self.reply(404, {"message": path})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0) or 0)) or "{}")
        if re.fullmatch(r"/app-manifests/\w+/conversions", self.path):
            # GitHub answers this one without credentials: the code is the secret.
            return self.reply(201, {"id": 4242, "slug": "agentfarm-e2e", "pem": open(os.environ["E2E_APP_PEM"]).read()})
        if not self.headers.get("Authorization", "").startswith("Bearer "):
            return self.reply(401, {"message": "Bad credentials"})
        if self.path == "/repos/e2e/demo/check-runs":
            # Only the panel's own installation token may publish: never an executor's.
            if self.headers["Authorization"] != "Bearer ghs_e2e":
                return self.reply(403, {"message": "Resource not accessible by integration"})
            runs = json.load(open(state + ".checks")) if os.path.exists(state + ".checks") else []
            runs.append({**body, "app": {"id": 4242, "slug": "agentfarm-e2e"}})
            json.dump(runs, open(state + ".checks", "w"))
            return self.reply(201, runs[-1])
        if re.fullmatch(r"/app/installations/\d+/access_tokens", self.path):
            if body.get("repositories"):
                # Like GitHub: the token carries the permissions asked for, or all of the App's.
                return self.reply(201, {"token": "ghs_e2e_repo_" + "_".join(body["repositories"]), "expires_at": "2099-01-01T00:00:00Z",
                                        "repository_selection": "selected", "permissions": body.get("permissions") or APP_PERMISSIONS})
            return self.reply(201, {"token": "ghs_e2e", "expires_at": "2099-01-01T00:00:00Z", "repository_selection": "all", "permissions": APP_PERMISSIONS})
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
