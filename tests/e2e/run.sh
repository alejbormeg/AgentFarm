#!/usr/bin/env bash
# End-to-end: real panel + real executor (FarmDaemon) with Codex and GitHub doubles.
# A task goes research -> owner approval -> implement -> sandboxed tests -> review -> PR.
# E2E_SANDBOX=host runs tests on the host (no Docker); the default uses a container.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
e2e="$root/tests/e2e"
work="$(mktemp -d)"
port="${E2E_PORT:-18090}"
panel="http://127.0.0.1:$port"
password="e2e-password-$(date +%s)-xyz"
sandbox="${E2E_SANDBOX:-container}"
pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
  if [[ "${E2E_KEEP:-0}" != 1 ]]; then rm -rf "$work"; else echo "Kept $work"; fi
}
trap cleanup EXIT
fail() {
  echo "E2E FAILED: $*" >&2
  echo "--- executor log" >&2; tail -40 "$work/executor.log" >&2 || true
  echo "--- panel log" >&2; tail -20 "$work/panel.log" >&2 || true
  exit 1
}

# Demo repository served as a local bare remote.
git init -q -b main "$work/seed"
git -C "$work/seed" -c user.name=e2e -c user.email=e2e@localhost commit -q --allow-empty -m init
echo "# Demo" > "$work/seed/README.md"
if [[ "$sandbox" == host ]]; then environment="environment: local"; else
  environment="environment: container
container: {image: 'bash:5.2', network: setup, timeout_minutes: 5}"
fi
cat > "$work/seed/agentfarm.yaml" <<YAML
version: 1
repository: e2e/demo
setup: ""
tests:
  unit: test -f greeting.txt || bash tests/test_fix.sh
  integration: grep -qs hola greeting.txt || bash tests/test_fix.sh
allowed_paths: [greeting.txt, fixed.txt, tests/, .github/workflows/]
$environment
review: independent
secret_refs: {}
quality:
  checks:
    lint: "true"
  metrics:
    files: ls | wc -l
YAML
git -C "$work/seed" add -A
git -C "$work/seed" -c user.name=e2e -c user.email=e2e@localhost commit -q -m readme
git clone -q --bare "$work/seed" "$work/remote.git"

# Panel, with an observer for the browser checks. The hash is of this test-only password.
observer_password=e2e-observer-password
printf '[{"username":"oscar","role":"observer","password_hash":"{bcrypt}%s"}]' \
  '$2b$10$1MgguADStmenKvMf9iRFiO0xWOluuogO3fCgW2i0mzVITaw6kMlty' > "$work/users.json"
AGENTFARM_PASSWORD="$password" AGENTFARM_DB="$work/panel/agentfarm.db" PORT="$port" \
  AGENTFARM_USERS_FILE="$work/users.json" \
  java -jar "$root/target/agentfarm.jar" > "$work/panel.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do curl -fs -o /dev/null "$panel/api/health" && break; sleep 1; done
curl -fs "$panel/api/health" > /dev/null || fail "panel did not start"

jar="$work/cookies"
csrf=$(curl -fs -u "admin:$password" -c "$jar" -b "$jar" "$panel/api/csrf" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
owner() { curl -fsS -u "admin:$password" -c "$jar" -b "$jar" -H "X-CSRF-TOKEN: $csrf" -H 'Content-Type: application/json' -X POST "$panel/api$1" -d "$2"; }
owner /budget/configure '{"account":"e2e-sub","mode":"subscription","shares":{"weekly":10000}}' > /dev/null
owner /nodes '{"id":"e2e-node","role":"napoleon"}' > /dev/null
owner /workers '{"id":"e2e-node-boxer","node":"e2e-node"}' > /dev/null
owner /workers '{"id":"e2e-node-reviewer","node":"e2e-node"}' > /dev/null
mkdir -p "$work/state"
owner /executors '{"node":"e2e-node","account":"e2e-sub"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])' > "$work/state/node-token"
chmod 600 "$work/state/node-token"
owner /work '{"request_id":"e2e-request-0001","repository":"e2e/demo","title":"Añadir saludo","request":"Crea greeting.txt con un saludo."}' > /dev/null

# Executor compiled from source, with the doubles.
cp="$root/target/classes:$(find "$HOME/.m2/repository/com/fasterxml/jackson/core" "$HOME/.m2/repository/org/yaml/snakeyaml" -name '*.jar' | paste -sd:)"
mkdir -p "$work/classes"
javac -nowarn -cp "$cp" -d "$work/classes" "$root/runtime/CodexRunner.java" "$root/runtime/FarmDaemon.java"
E2E_GH_STATE="$work/gh.json" E2E_REMOTE="$work/remote.git" \
  java -cp "$work/classes:$cp" \
  -Dagentfarm.url="$panel" -Dagentfarm.state="$work/state" \
  -Dagentfarm.node=e2e-node -Dagentfarm.account=e2e-sub \
  -Dagentfarm.codex="$e2e/fake-codex" -Dagentfarm.gh="$e2e/fake_gh.py" \
  -Dagentfarm.sandbox="$sandbox" -Dagentfarm.sandbox-image=bash:5.2 \
  FarmDaemon > "$work/executor.log" 2>&1 &
executor=$!
pids+=($executor)

state() {
  curl -fs -u "admin:$password" "$panel/api/work" \
    | python3 -c 'import json,sys;w=[x for x in json.load(sys.stdin) if x["id"]==sys.argv[1]][0];print(w["state"], w.get("error") or "")' "$item"
}
item=e2e-request-0001
wait_for() {
  local want="$1" limit="$2" s=""
  for _ in $(seq 1 "$limit"); do
    s=$(state)
    [[ "$s" == "$want"* ]] && return 0
    [[ "$s" == blocked* ]] && fail "blocked while waiting for $want: $s"
    kill -0 "$executor" 2>/dev/null || fail "executor exited while waiting for $want"
    sleep 2
  done
  fail "timeout waiting for $want (last: $s)"
}

wait_for proposed 60
echo "Research proposed; approving version 1"
owner /work/e2e-request-0001/actions/implement '{"version":1}' > /dev/null
wait_for review 150

python3 - "$panel" "$password" "$work/gh.json" <<'PY'
import base64, json, sys, urllib.request
panel, password, gh = sys.argv[1:]
req = urllib.request.Request(panel + "/api/work", headers={"Authorization": "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()})
item = [x for x in json.load(urllib.request.urlopen(req)) if x["id"] == "e2e-request-0001"][0]
evidence = json.loads(item["evidence"])
assert item["pr"] == 1, item
assert len(item["sha"]) == 40 and item["branch"].startswith("agentfarm/"), item
assert evidence["passed"] is True, evidence
assert evidence["unit"]["status"] == "passed" and evidence["integration"]["status"] == "passed", evidence
assert evidence["review"]["passed"] is True
assert evidence["prompts"]["review"]["id"] == "review.v2"
pulls = json.load(open(gh))["pulls"]
assert pulls["1"]["draft"] is False and pulls["1"]["head"] == item["branch"], pulls
print("E2E OK:", item["state"], "PR", item["pr"], item["sha"][:12], "sandbox", evidence["unit"].get("sandbox", {}).get("mode", "host"))
PY
git -C "$work/remote.git" rev-parse --verify -q "refs/heads/$(state >/dev/null; curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print([x for x in json.load(sys.stdin) if x["id"]=="e2e-request-0001"][0]["branch"])')" > /dev/null \
  || fail "branch not pushed to the remote"
echo "Branch pushed to remote"
branch=$(git -C "$work/remote.git" branch --list 'agentfarm/*' | tr -d ' *' | head -1)
git -C "$work/remote.git" show "$branch:.github/workflows/agentfarm.yml" | python3 -c '
import json, sys
jobs = json.load(sys.stdin)["jobs"]
mode = sys.argv[1]
for name, job in jobs.items():
    if mode == "host":
        assert "container" not in job, job
    else:
        assert job["container"]["image"] == "bash:5.2", job
print("CI template runs in the sandbox image:", mode != "host")
' "$sandbox"

# A bug fix: the agent's new test must fail on the base commit, and the project's gates must run.
owner /work '{"request_id":"e2e-request-0002","repository":"e2e/demo","title":"Corrige el saludo","request":"Corrige el saludo.","recipe":{"id":"bugfix","version":1}}' > /dev/null
item=e2e-request-0002
wait_for proposed 60
owner /work/e2e-request-0002/actions/implement '{"version":1}' > /dev/null
wait_for review 200
python3 - "$panel" "$password" "$sandbox" <<'PY'
import base64, json, sys, urllib.request
panel, password, sandbox = sys.argv[1:]
req = urllib.request.Request(panel + "/api/work", headers={"Authorization": "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()})
work = {x["id"]: x for x in json.load(urllib.request.urlopen(req))}
first, fix = (json.loads(work["e2e-request-000%d" % i]["evidence"]) for i in (1, 2))
assert first["regression"]["status"] == "not_required", first
assert first["untested_code"] is False, first
assert fix["quality"]["checks"][0]["status"] == "passed", fix["quality"]
assert fix["skills"]["enabled"] is True and {"change-discipline", "test-design"} <= {s["id"] for s in fix["skills"]["implement"]}, fix["skills"]
assert {"code-review", "test-design"} <= {s["id"] for s in fix["skills"]["review"]}, fix["skills"]
assert any(s["id"] == "software-design" for s in fix["skills"]["research"]), fix["skills"]
if sandbox == "host":
    assert fix["regression"]["status"] == "not_evaluated", fix["regression"]
else:
    assert fix["regression"]["status"] == "proved", fix["regression"]
    assert fix["quality"]["metrics"][0]["status"] == "passed", fix["quality"]
print("Gates OK: regression", fix["regression"]["status"], "quality", fix["quality"]["metrics"])
PY

# The panel with a task in review and a live executor, seen through a browser.
if [[ -n "${E2E_CHROME:-}" ]]; then
  CHROME="$E2E_CHROME" node "$root/tests/ui/smoke.mjs" "$panel" "$password" "$observer_password" \
    || fail "browser smoke test"
else
  echo "Browser smoke test skipped: set E2E_CHROME to a Chrome binary"
fi

# Adoption in a repository without a profile: an invalid candidate stays a draft and can be corrected.
git -C "$work/seed" pull -q --ff-only "$work/remote.git" main 2>/dev/null || true
git -C "$work/seed" rm -q agentfarm.yaml
git -C "$work/seed" -c user.name=e2e -c user.email=e2e@localhost commit -q -m "Sin perfil"
git -C "$work/seed" push -q "$work/remote.git" HEAD:main
adopt_main=$(git -C "$work/seed" rev-parse HEAD)
owner /work '{"request_id":"e2e-request-adopt","repository":"e2e/demo","title":"Adoptar","request":"Adopta este repositorio.","recipe":{"id":"adopt-repo","version":2}}' > /dev/null
item=e2e-request-adopt
wait_for proposed 60
owner /work/e2e-request-adopt/actions/implement '{"version":1}' > /dev/null
wait_for review 150
adoption() {
  python3 - "$panel" "$password" "$work/gh.json" "$1" "$adopt_main" <<'PY'
import base64, json, sys, urllib.request
panel, password, gh, step, main = sys.argv[1:]
req = urllib.request.Request(panel + "/api/work", headers={"Authorization": "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()})
item = [x for x in json.load(urllib.request.urlopen(req)) if x["id"] == "e2e-request-adopt"][0]
evidence = json.loads(item["evidence"])
pull = json.load(open(gh))["pulls"][str(item["pr"])]
assert evidence["base_sha"] == main, evidence["base_sha"]
if step == "invalid":
    assert evidence["profile_validation"]["status"] == "failed", evidence["profile_validation"]
    assert evidence["passed"] is False and pull["draft"] is True, (evidence["passed"], pull)
else:
    assert evidence["profile_validation"]["status"] == "passed", evidence["profile_validation"]
    assert evidence["passed"] is True and pull["draft"] is False, (evidence["passed"], pull)
    assert evidence["quality"]["checks"][0]["status"] == "passed", evidence["quality"]
print("Adoption", step, "OK: PR", item["pr"], item["sha"][:12])
PY
}
adoption invalid || fail "invalid adoption candidate"
owner /work/e2e-request-adopt/actions/correct '{"feedback":"El perfil no carga: usa el formato real."}' > /dev/null
sleep 3
wait_for proposed 60
owner /work/e2e-request-adopt/actions/implement '{"version":2}' > /dev/null
sleep 3
wait_for review 150
adoption corrected || fail "corrected adoption candidate"
