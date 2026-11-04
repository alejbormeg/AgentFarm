#!/usr/bin/env python3
"""The pilot command against a scripted panel: it stops on its own limits and presses nothing extra."""
import json, os, subprocess, sys, tempfile, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))


class Fake:
    def __init__(self, states, quota=(10,), fresh=True, ready=True, evidence=None):
        self.states, self.quota, self.fresh, self.ready, self.evidence = list(states), list(quota), fresh, ready, evidence
        self.posts, self.item = [], None

    def handler(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def send(self, value):
                body = json.dumps(value).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                path = self.path.removeprefix("/api")
                if path == "/csrf":
                    self.send({"header": "X-CSRF-TOKEN", "token": "t"})
                elif path == "/version":
                    self.send({"commit": "abc"})
                elif path == "/runtime":
                    used = fake.quota.pop(0) if len(fake.quota) > 1 else fake.quota[0]
                    self.send({"quota_fresh": fake.fresh, "snapshot": {"quota": {"weekly_used_percent": used}}})
                elif path == "/work":
                    state = fake.states.pop(0) if len(fake.states) > 1 else fake.states[0]
                    self.send([{"id": fake.item, "state": state, "version": 1, "pr": 7, "sha": "a" * 40, "error": "motivo",
                                "evidence": json.dumps(fake.evidence) if fake.evidence else None}])
                elif path.startswith("/evidence/"):
                    self.send({"ready": fake.ready, "blockers": ["falta CI"]})
                else:
                    self.send_error(404)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                path = self.path.removeprefix("/api")
                fake.posts.append((path, body))
                if path == "/work":
                    fake.item = body["request_id"]
                self.send({})

        return Handler


def run(fake, *flags):
    server = HTTPServer(("127.0.0.1", 0), fake.handler())
    threading.Thread(target=server.serve_forever, daemon=True).start()
    with tempfile.NamedTemporaryFile("w", suffix=".txt") as password:
        password.write("secret-password\n")
        password.flush()
        done = subprocess.run(
            [sys.executable, os.path.join(HERE, "run.py"), "--repo", "o/r", "--panel", f"http://127.0.0.1:{server.server_port}",
             "--password-file", password.name, "--poll", "0.01", *flags],
            capture_output=True, text=True, timeout=60)
    server.shutdown()
    assert "secret-password" not in done.stdout + done.stderr, "the password must not be printed"
    return done.returncode, json.loads(done.stdout)


def actions(fake):
    return [p.rsplit("/", 1)[-1] for p, _ in fake.posts]


fake = Fake(["queued", "proposed", "claimed", "review", "merging", "integrated"], quota=(10, 12))
code, report = run(fake, "--merge")
assert code == 0 and report["integrated"] == 1 and report["blocked"] == [], report
assert actions(fake) == ["work", "implement", "merge"], actions(fake)
assert fake.posts[-1][1] == {"sha": "a" * 40}, "merges the exact SHA it reviewed"
assert fake.posts[0][1]["recipe"] == {"id": "adopt-repo", "version": 2}
assert report["quota_per_integrated_task"] == 2.0, report
print("ok   adopts, implements and integrates by SHA")

fake = Fake(["proposed", "review"])
code, report = run(fake)
assert code == 0 and report["integrated"] == 0 and "merge" not in actions(fake), report
print("ok   does not merge unless asked")

fake = Fake(["proposed", "review"])
code, report = run(fake, "--skip-adoption", "--request", "Corrige algo")
assert code == 0 and len(report["tasks"]) == 1 and "recipe" not in fake.posts[0][1], report
print("ok   runs a task on an adopted repository")

fake = Fake(["proposed"], quota=(95,))
code, report = run(fake)
assert code == 1 and fake.posts == [] and "límite" in report["stopped"], report
print("ok   launches nothing above the quota limit")

fake = Fake(["proposed"], fresh=False)
code, report = run(fake)
assert code == 1 and fake.posts == [] and "desconocida" in report["stopped"], report
print("ok   launches nothing with an unknown quota")

fake = Fake(["queued", "blocked"])
code, report = run(fake)
assert code == 1 and actions(fake) == ["work"], actions(fake)
assert report["blocked"][0]["error"] == "motivo" and report["integrated"] == 0, report
print("ok   reports a block and does not work around it")

fake = Fake(["queued"], quota=(10, 95))
code, report = run(fake, "--timeout", "30")
assert code == 1 and "límite" in report["stopped"] and actions(fake) == ["work"], report
print("ok   stops when the quota reaches the limit mid-way")

fake = Fake(["proposed", "review"], ready=False)
code, report = run(fake, "--merge", "--timeout", "1")
assert code == 1 and "merge" not in actions(fake) and report["blocked"][0]["error"] == "['falta CI']", report
print("ok   does not merge what the panel says is not ready")

blocked = {"passed": False, "review": {"passed": False, "summary": "Falta una prueba"}}
fake = Fake(["proposed", "review"], evidence=blocked)
code, report = run(fake, "--merge")
assert code == 1 and "merge" not in actions(fake), report
assert report["blocked"][0]["error"] == "Falta una prueba" and report["tasks"][0]["review_passed"] is False, report
print("ok   a candidate the reviewer blocked is not a success")

fake = Fake(["proposed", "review"], evidence={"passed": True, "review": {"passed": True}})
code, report = run(fake)
assert code == 0 and report["blocked"] == [] and report["tasks"][0]["review_passed"] is True, report
print("ok   a candidate with passing evidence goes on")

fake = Fake(["proposed", "review", "merging", "integrated"], quota=(10,))
code, report = run(fake, "--merge")
assert code == 0 and report["quota_per_integrated_task"] is None and "menos de 1" in report["quota_note"], report
print("ok   an unmeasurable cost is not reported as zero")

print("Pilot checks passed")
