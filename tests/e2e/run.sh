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
if [[ "$sandbox" == host ]]; then
  cat > "$work/seed/agentfarm.yaml" <<'YAML'
version: 1
repository: e2e/demo
setup: ""
tests:
  unit: test -f greeting.txt
  integration: grep -q hola greeting.txt
allowed_paths: [greeting.txt, .github/workflows/]
environment: local
review: independent
secret_refs: {}
YAML
fi
git -C "$work/seed" add -A
git -C "$work/seed" -c user.name=e2e -c user.email=e2e@localhost commit -q -m readme
git clone -q --bare "$work/seed" "$work/remote.git"

# Panel.
AGENTFARM_PASSWORD="$password" AGENTFARM_DB="$work/panel/agentfarm.db" PORT="$port" \
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
pids+=($!)

state() {
  curl -fs -u "admin:$password" "$panel/api/work" \
    | python3 -c 'import json,sys;w=[x for x in json.load(sys.stdin) if x["id"]=="e2e-request-0001"][0];print(w["state"], w.get("error") or "")'
}
wait_for() {
  local want="$1" limit="$2" s=""
  for _ in $(seq 1 "$limit"); do
    s=$(state)
    [[ "$s" == "$want"* ]] && return 0
    [[ "$s" == blocked* ]] && fail "blocked while waiting for $want: $s"
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
assert evidence["prompts"]["review"]["id"] == "review.v1"
pulls = json.load(open(gh))["pulls"]
assert pulls["1"]["draft"] is False and pulls["1"]["head"] == item["branch"], pulls
print("E2E OK:", item["state"], "PR", item["pr"], item["sha"][:12], "sandbox", evidence["unit"].get("sandbox", {}).get("mode", "host"))
PY
git -C "$work/remote.git" rev-parse --verify -q "refs/heads/$(state >/dev/null; curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print([x for x in json.load(sys.stdin) if x["id"]=="e2e-request-0001"][0]["branch"])')" > /dev/null \
  || fail "branch not pushed to the remote"
echo "Branch pushed to remote"
