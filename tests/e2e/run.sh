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

# The panel's pure view logic, without a browser.
node --test "$root"/tests/ui/*.test.mjs > "$work/ui-unit.log" 2>&1 || { cat "$work/ui-unit.log" >&2; fail "the panel's view logic"; }

# The executor as it ships: a class missing from the package must fail here. Before the panel starts, as this rebuilds its jar.
bash "$root/runtime/package.sh" > "$work/package.log" 2>&1 || { cat "$work/package.log" >&2; fail "the executor does not package"; }
tar -xzf "$root/target/agentfarm-executor-linux.tar.gz" -C "$work"
dist="$work/agentfarm-executor"
executor_jar="$dist/executor.jar"

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
# GitHub as the panel sees it, sharing the PR state with the gh double the executor uses.
api_port=$((port + 1))
echo '{"issues": {}, "pulls": {}}' > "$work/gh.json"
openssl genrsa -traditional -out "$work/app.pem" 2048 2>/dev/null
E2E_APP_PEM="$work/app.pem" python3 "$e2e/fake_github_api.py" "$api_port" "$work/gh.json" "$work/remote.git" > "$work/api.log" 2>&1 &
pids+=($!)
echo e2e-panel-token > "$work/github-token"
# The panel's commit, as an image build gives it: the packaged executor must match, and only another commit warn.
mkdir "$work/build"
git -C "$root" rev-parse HEAD > "$work/build/build-commit.txt"
AGENTFARM_PASSWORD="$password" AGENTFARM_DB="$work/panel/agentfarm.db" PORT="$port" \
  AGENTFARM_USERS_FILE="$work/users.json" AGENTFARM_CI_WAIT_SECONDS=1 \
  AGENTFARM_GITHUB_TOKEN_FILE="$work/github-token" AGENTFARM_GITHUB_API="http://127.0.0.1:$api_port" \
  java -Xbootclasspath/a:"$work/build" -jar "$root/target/agentfarm.jar" > "$work/panel.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do curl -fs -o /dev/null "$panel/api/health" && break; sleep 1; done
curl -fs "$panel/api/health" > /dev/null || fail "panel did not start"

# The credential check, through the real client and against what GitHub answers.
AGENTFARM_GITHUB_TOKEN_FILE="$work/github-token" AGENTFARM_GITHUB_API="http://127.0.0.1:$api_port" \
  java -jar "$root/target/agentfarm.jar" github-check --repo e2e/demo > "$work/github-check.json" \
  || fail "github-check refused the credential: $(cat "$work/github-check.json")"
python3 - "$work/github-check.json" <<'PY' || fail "github-check did not read the write permission"
import json, sys
checks = {c["id"]: c["state"] for c in json.load(open(sys.argv[1]))["checks"]}
assert checks["write"] == "passed", checks
print("github-check OK:", checks)
PY

jar="$work/cookies"
csrf=$(curl -fs -u "admin:$password" -c "$jar" -b "$jar" "$panel/api/csrf" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
owner() { curl -fsS -u "admin:$password" -c "$jar" -b "$jar" -H "X-CSRF-TOKEN: $csrf" -H 'Content-Type: application/json' -X POST "$panel/api$1" -d "$2"; }
owner /budget/configure '{"account":"e2e-sub","mode":"subscription","shares":{"weekly":10000}}' > /dev/null
owner /budget/configure '{"account":"e2e-api","mode":"api","shares":{"weekly":10000}}' > /dev/null
owner /budget/open-window "{\"account\":\"e2e-api\",\"period\":\"weekly\",\"start\":$(($(date +%s) - 3600)),\"end\":$(($(date +%s) + 7 * 86400)),\"total\":50000000,\"used\":0,\"observed_at\":$(($(date +%s) - 60)),\"max_age\":86400}" > /dev/null
owner /nodes '{"id":"e2e-node","role":"napoleon"}' > /dev/null
owner /workers '{"id":"e2e-node-boxer","node":"e2e-node"}' > /dev/null
owner /workers '{"id":"e2e-node-reviewer","node":"e2e-node"}' > /dev/null
mkdir -p "$work/state"
owner /executors '{"node":"e2e-node","account":"e2e-sub","api_account":"e2e-api"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])' > "$work/state/node-token"
chmod 600 "$work/state/node-token"
# The owner's per-repository instructions must reach every agent of that repository.
# The owner picks a provider per role from the panel; unknown providers are refused.
owner /executors/e2e-node/providers '{"roles":{"review":"antigravity"}}' | grep -q antigravity || fail "the provider choice was not saved"
curl -fs -u "admin:$password" "$panel/api/executors/e2e-node/providers" | grep -q '"review":"antigravity"' || fail "the provider choice was not kept"
if owner /executors/e2e-node/providers '{"roles":{"review":"skynet"}}' > /dev/null 2>&1; then fail "an unknown provider was accepted"; fi
# A node added from the panel: the one-use code is exchanged for a credential once, with no session.
enroll_code=$(owner /executors/enroll '{"node":"e2e-extra"}' | python3 -c 'import json,sys;d=json.load(sys.stdin);assert d["command"].endswith("--code "+d["code"]),d;print(d["code"])') || fail "the panel did not give an enrolment code"
extra_token=$(curl -fsS -H 'Content-Type: application/json' -X POST "$panel/executor/redeem" -d "{\"code\":\"$enroll_code\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])') || fail "the enrolment code was not accepted"
curl -fs -H "Authorization: Bearer $extra_token" "$panel/api/executors/me" | grep -q e2e-extra || fail "the enrolled credential does not identify its node"
if curl -fs -o /dev/null -H 'Content-Type: application/json' -X POST "$panel/executor/redeem" -d "{\"code\":\"$enroll_code\"}"; then fail "an enrolment code worked twice"; fi
echo "Node enrolled with a one-use code"
owner /repos/e2e/demo/agents '{"roles":{"research":{"system":"MARCA-NAPOLEON"},"implement":{"system":"MARCA-BOXER"},"review":{"system":"MARCA-BENJAMIN"}},"skills":[{"id":"casa-e2e","title":"Casa","roles":["implement"],"text":"MARCA-SKILL"}]}' \
  | python3 -c 'import json,sys;d=json.load(sys.stdin);assert d["roles"]["research"]["custom"] and d["skills"][0]["id"]=="casa-e2e", d' || fail "the agent profile was not saved"
curl -fs -u "admin:$password" "$panel/api/repos/e2e/demo/agents/export?format=gemini" | grep -q "MARCA-SKILL" || fail "the Gemini export lacks the skill"
owner /work '{"request_id":"e2e-request-0001","repository":"e2e/demo","title":"Añadir saludo","request":"Crea greeting.txt con un saludo."}' > /dev/null

export E2E_PROMPT_LOG="$work/marks.log"
export E2E_QUOTA_RESETS=$(($(date +%s) + 3 * 86400))
start_executor() {
  E2E_GH_STATE="$work/gh.json" E2E_REMOTE="$work/remote.git" \
    java -cp "$executor_jar:$dist/lib/*" \
    -Dagentfarm.url="$panel" -Dagentfarm.state="$work/state" \
    -Dagentfarm.node=e2e-node -Dagentfarm.account=e2e-sub \
    -Dagentfarm.codex="$e2e/fake-codex" -Dagentfarm.gh="$e2e/fake_gh.py" \
    -Dagentfarm.provider.review=antigravity -Dagentfarm.antigravity="$e2e/fake-codex" -Dagentfarm.antigravity-account=e2e-api \
    -Dagentfarm.sandbox="$sandbox" -Dagentfarm.sandbox-image=bash:5.2 \
    FarmDaemon > "$work/executor.log" 2>&1 &
  executor=$!
  pids+=($executor)
}

# An update of Codex that stops hiding a path must stop the work, and the panel must say so.
export E2E_SANDBOX_BROKEN=1
state_before=$(curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["state"])')
start_executor
refused=0
for _ in $(seq 1 30); do
  refused=$(curl -fs -u "admin:$password" "$panel/api/runtime" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["unisolated_executors"]))')
  [[ "$refused" == 1 ]] && break
  sleep 1
done
[[ "$refused" == 1 ]] || fail "the panel does not warn about a broken sandbox"
sleep 3
state_after=$(curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["state"])')
[[ "$state_after" == "$state_before" ]] || fail "a broken sandbox still claimed work: $state_after"
echo "Broken sandbox refused: the task stays $state_after"
kill "$executor"
wait "$executor" 2>/dev/null || true
unset E2E_SANDBOX_BROKEN

# A broken executor must stop, so systemd restarts it. It once stayed up and reported itself healthy with no loop taking orders.
mkdir "$work/damaged"
(cd "$work/damaged" && jar xf "$dist/executor.jar" && rm -f 'FarmDaemon$Task.class' && jar --create --file "$work/damaged.jar" .)
executor_jar="$work/damaged.jar"
start_executor
for _ in $(seq 1 60); do
  kill -0 "$executor" 2>/dev/null || break
  sleep 1
done
kill -0 "$executor" 2>/dev/null && fail "an executor whose main loop died stays alive"
status=0
wait "$executor" || status=$?
[[ "$status" != 0 ]] || fail "an executor whose main loop died exits with success"
grep -q 'NoClassDefFoundError' "$work/executor.log" || fail "the damaged executor exited for another reason"
echo "Damaged executor stopped with status $status; the next one receives its order again"
executor_jar="$dist/executor.jar"

start_executor

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
item = json.load(urllib.request.urlopen(urllib.request.Request(panel + "/api/work/e2e-request-0001", headers=req.headers)))
evidence = json.loads(item["evidence"])
assert item["pr"] == 1, item
assert len(item["sha"]) == 40 and item["branch"].startswith("agentfarm/"), item
assert evidence["passed"] is True, evidence
assert evidence["unit"]["status"] == "passed" and evidence["integration"]["status"] == "passed", evidence
assert evidence["review"]["passed"] is True
assert evidence["prompts"]["review"]["id"] == "review.v3"
spent = json.loads(urllib.request.urlopen(urllib.request.Request(panel + "/api/budget/e2e-api", headers=req.headers)).read())
assert any(w["used"] > 0 for w in spent["windows"]), ("the Antigravity review was not charged to Clover", spent)
proposal = json.loads(item["proposal"])
assert proposal["headline"].startswith("Falta un saludo"), proposal
assert [n["type"] for n in proposal["to_be_graph"]["nodes"]] == ["file", "file"], proposal
assert proposal["to_be"].startswith("flowchart LR") and "greeting.txt" in proposal["to_be"], proposal
pulls = json.load(open(gh))["pulls"]
assert pulls["1"]["draft"] is False and pulls["1"]["head"] == item["branch"], pulls
print("E2E OK:", item["state"], "PR", item["pr"], item["sha"][:12], "sandbox", evidence["unit"].get("sandbox", {}).get("mode", "host"))
PY
for mark in MARCA-NAPOLEON MARCA-BOXER MARCA-BENJAMIN MARCA-SKILL; do
  grep -q -- "$mark" "$work/marks.log" || fail "the owner instructions never reached $mark"
done
echo "The owner instructions reached Napoleon, Boxer, Benjamin and the skill"
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
summaries = json.load(urllib.request.urlopen(req))
assert all("evidence" not in x and "proposal" not in x for x in summaries), "the list carries summaries only"
detail = lambda i: json.load(urllib.request.urlopen(urllib.request.Request(panel + "/api/work/" + i, headers=req.headers)))
first, fix = (json.loads(detail("e2e-request-000%d" % i)["evidence"]) for i in (1, 2))
v6 = json.loads(detail("e2e-request-0002")["proposal"])
assert "to_be_graph" not in v6 and v6["to_be"].startswith("flowchart LR"), v6
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

# A credential at the start of a large diff stops the task before anything is pushed: the
# runtime keeps only the tail of a command's output, so the scan must read Git directly.
item=e2e-request-leak
branches_before=$(git -C "$work/remote.git" for-each-ref --format='%(refname)' refs/heads | sort)
owner /work '{"request_id":"e2e-request-leak","repository":"e2e/demo","title":"Fixture","request":"Añade un fixture con la credencial filtrada en tests/."}' > /dev/null
wait_for proposed 60
owner /work/e2e-request-leak/actions/implement '{"version":1}' > /dev/null
leak=""
for _ in $(seq 1 150); do
  leak=$(state)
  [[ "$leak" == blocked* ]] && break
  sleep 2
done
[[ "$leak" == blocked*"Posible secreto"*"tests/fixture.txt"* ]] || fail "a leaked credential was not stopped before the push: $leak"
[[ "$leak" != *ghp_* ]] || fail "the error shows the credential"
[[ "$(git -C "$work/remote.git" for-each-ref --format='%(refname)' refs/heads | sort)" == "$branches_before" ]] \
  || fail "a candidate with a credential reached the remote"
owner /work/e2e-request-leak/actions/cancel '{}' > /dev/null || true
echo "Credential at the start of a large diff stopped before the push"

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
item = json.load(urllib.request.urlopen(urllib.request.Request(panel + "/api/work/e2e-request-adopt", headers=req.headers)))
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
    assert "summary" in [c["field"] for c in json.loads(item["proposal"])["previous_changes"]], item["proposal"]
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

# A task whose branch the base has moved past starts over from the base on the same PR.
item=e2e-request-0001
old_head=$(git -C "$work/remote.git" rev-parse "$branch")
approved_criteria=$(python3 - "$panel" "$password" <<'PY'
import base64, json, sys, urllib.request
panel, password = sys.argv[1:]
req = urllib.request.Request(panel + "/api/work", headers={"Authorization": "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()})
item = json.load(urllib.request.urlopen(urllib.request.Request(panel + "/api/work/e2e-request-0001", headers=req.headers)))
print(json.dumps([c["description"] for c in json.loads(item["proposal"])["criteria"]]))
PY
)
owner /work/e2e-request-0001/actions/correct '{"feedback":"La base ha cambiado: actualiza la propuesta. Reformula los criterios."}' > /dev/null
sleep 3
wait_for proposed 60
python3 - "$panel" "$password" "$approved_criteria" <<'PY' || fail "a correction lost the approved criteria"
import base64, json, sys, urllib.request
panel, password, approved = sys.argv[1:]
req = urllib.request.Request(panel + "/api/work", headers={"Authorization": "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()})
item = json.load(urllib.request.urlopen(urllib.request.Request(panel + "/api/work/e2e-request-0001", headers=req.headers)))
now = [c["description"] for c in json.loads(item["proposal"])["criteria"]]
assert now[:len(json.loads(approved))] == json.loads(approved), now
assert "Cubre solo la última corrección" in now, now
PY
echo "Approved criteria survived a correction"
owner /work/e2e-request-0001/actions/implement '{"version":2}' > /dev/null
sleep 3
wait_for review 150
python3 - "$panel" "$password" "$adopt_main" <<'PY' || fail "stale branch was not rebuilt"
import base64, json, sys, urllib.request
panel, password, main = sys.argv[1:]
req = urllib.request.Request(panel + "/api/work", headers={"Authorization": "Basic " + base64.b64encode(f"admin:{password}".encode()).decode()})
item = json.load(urllib.request.urlopen(urllib.request.Request(panel + "/api/work/e2e-request-0001", headers=req.headers)))
evidence = json.loads(item["evidence"])
assert evidence["base_sha"] == main, evidence["base_sha"]
assert evidence["passed"] is True and item["pr"] == 1, item
print("Stale branch rebuilt on the base: PR", item["pr"], item["sha"][:12])
PY
git -C "$work/remote.git" merge-base --is-ancestor "$adopt_main" "$branch" || fail "rebuilt branch does not contain the base"
[[ "$(git -C "$work/remote.git" rev-parse "$branch")" != "$old_head" ]] || fail "branch not replaced"

# The panel's notices, in the browser. They need the state this run has reached.
if [[ -n "${E2E_CHROME:-}" ]]; then
  item=e2e-request-overlap
  owner /work '{"request_id":"e2e-request-overlap","repository":"e2e/demo","title":"Añadir otro saludo","request":"Crea greeting.txt con otro saludo."}' > /dev/null
  wait_for proposed 60
  owner /work/$item/actions/implement '{"version":1}' > /dev/null
  wait_for review 150
  overlaps=0
  for _ in $(seq 1 45); do
    overlaps=$(curl -fs -u "admin:$password" "$panel/api/evidence/$item" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["overlaps"]))')
    [[ "$overlaps" != 0 ]] && break
    sleep 2
  done
  [[ "$overlaps" != 0 ]] || fail "the panel did not see two tasks on the same files"
  # A repository whose CI never starts: the panel says why, and the gate stays shut.
  item=e2e-request-noci
  touch "$work/gh.json.no-ci"
  owner /work '{"request_id":"e2e-request-noci","repository":"e2e/demo","title":"Añadir una despedida","request":"Crea greeting.txt con una despedida."}' > /dev/null
  wait_for proposed 60
  owner /work/$item/actions/implement '{"version":1}' > /dev/null
  wait_for review 150
  stalled=""
  for _ in $(seq 1 45); do
    stalled=$(curl -fs -u "admin:$password" "$panel/api/evidence/$item" | python3 -c 'import json,sys;d=json.load(sys.stdin);print("yes" if d["verification"].get("detail",{}).get("stalled") and not d["ready"] else "")')
    [[ -n "$stalled" ]] && break
    sleep 2
  done
  [[ -n "$stalled" ]] || fail "a CI that never starts was not explained"
  # A slot of this node with another commit and a broken sandbox; a warning only lasts while it reports.
  node_jar="$work/node-cookies"
  node_csrf=$(curl -fs -H "Authorization: Bearer $(cat "$work/state/node-token")" -c "$node_jar" -b "$node_jar" "$panel/api/csrf" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
  (
    while :; do
      curl -fs -o /dev/null -H "Authorization: Bearer $(cat "$work/state/node-token")" -H "X-CSRF-TOKEN: $node_csrf" \
        -c "$node_jar" -b "$node_jar" -H 'Content-Type: application/json' \
        -X POST "$panel/api/runtime/observations" \
        -d '{"snapshot":{"node":"e2e-node","executor":"e2e-node-s9","build":"deadbeef","sandbox_failure":"EXPUESTO /ruta","isolation_notice":"Claude Code solo tiene reglas de bloqueo."},"events":[]}' || true
      sleep 3
    done
  ) &
  reporter=$!
  pids+=($reporter)
  for _ in $(seq 1 10); do
    curl -fs -u "admin:$password" "$panel/api/runtime" | python3 -c 'import json,sys;sys.exit(0 if json.load(sys.stdin)["unisolated_executors"] else 1)' && break
    sleep 1
  done
  SMOKE_NOTICES=1 CHROME="$E2E_CHROME" node "$root/tests/ui/smoke.mjs" "$panel" "$password" || fail "browser notices"
  kill "$reporter" 2>/dev/null || true
  rm -f "$work/gh.json.no-ci"
  owner /work/e2e-request-noci/actions/cancel '{}' > /dev/null
else
  echo "Browser notices skipped: set E2E_CHROME to a Chrome binary"
fi

# The last stretch: the panel verifies the candidate, refuses another SHA and integrates this one.
item=e2e-request-adopt
sha=$(curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print([x for x in json.load(sys.stdin) if x["id"]=="e2e-request-adopt"][0]["sha"])')
ready=false
for _ in $(seq 1 60); do
  ready=$(curl -fs -u "admin:$password" "$panel/api/evidence/$item" | python3 -c 'import json,sys;print(str(json.load(sys.stdin)["ready"]).lower())')
  [[ "$ready" == true ]] && break
  sleep 2
done
[[ "$ready" == true ]] || fail "acceptance never ready: $(curl -fs -u "admin:$password" "$panel/api/evidence/$item" | python3 -c 'import json,sys;print(json.load(sys.stdin)["blockers"])')"
if owner "/work/$item/actions/merge" "{\"sha\":\"$(printf '0%.0s' $(seq 1 40))\"}" > /dev/null 2>&1; then fail "merged another SHA"; fi
owner "/work/$item/actions/merge" "{\"sha\":\"$sha\"}" > /dev/null
wait_for integrated 30
git -C "$work/remote.git" show main:agentfarm.yaml > /dev/null || fail "integration did not reach main"
git -C "$work/remote.git" show main:adopted.txt > /dev/null || fail "integration did not reach main"
python3 -c 'import json,sys;assert json.load(open(sys.argv[1]))["pulls"]["3"]["merged"] is True' "$work/gh.json" || fail "PR not merged"
echo "Integrated from the panel by SHA: $sha"

# Connecting GitHub from the panel: the manifest flow, then a real credential check with what it stored.
location() { curl -s -o /dev/null -w '%{redirect_url}' -u "admin:$password" "$1"; }
state_of() { python3 -c 'import json,sys;print(json.loads(sys.stdin.read())["action"].split("state=")[1])'; }
started=$(owner /github-app/start '{}')
python3 -c 'import json,sys;r=json.loads(sys.argv[1]);m=json.loads(r["manifest"]);assert "hook_attributes" not in m and r["webhook"] is False,m' "$started" \
  || fail "a local panel declared a webhook GitHub cannot reach"
curl -fs -u "admin:$password" "$panel/api/github-app" | python3 -c 'import json,sys;assert json.load(sys.stdin)["webhook"] is False' \
  || fail "Gobierno does not know the App will have no webhook"
first=$(state_of <<<"$started")
# 403, or 401 if the browser checks gave this account a second factor.
[[ "$(curl -s -o /dev/null -w '%{http_code}' -u "oscar:$observer_password" "$panel/github-app/created?code=abc&state=$first")" == 40[13] ]] \
  || fail "an observer reached the GitHub App flow"
[[ "$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$password" "$panel/github-app/created?code=abc&state=forged")" == 400 ]] \
  || fail "a forged state was accepted"
install=$(location "$panel/github-app/created?code=abc&state=$first")
[[ "$install" == */apps/agentfarm-e2e/installations/new\?state=* ]] || fail "no installation page: $install"
done_at=$(location "$panel/github-app/installed?installation_id=99&state=${install##*state=}")
[[ "$done_at" == */governance ]] || fail "installation not recorded: $done_at"
[[ "$(stat -c %a "$work/panel/github-app.pem")" == 600 ]] || fail "the App key is not private"
AGENTFARM_DB="$work/panel/agentfarm.db" AGENTFARM_GITHUB_API="http://127.0.0.1:$api_port" \
  java -jar "$root/target/agentfarm.jar" github-check --repo e2e/demo > "$work/app-check.json" \
  || fail "github-check refused the stored App: $(cat "$work/app-check.json")"
python3 -c 'import json,sys;r=json.load(open(sys.argv[1]));assert r["credential"]=="github-app",r;print("GitHub App connected from the panel:",r["state"])' "$work/app-check.json" \
  || fail "the stored App was not used"

# With an App, the executor needs no gh login: each order gets a token for its repository from the panel.
if owner /work/agent/commands/none/github-token '{"agent":"admin"}' > /dev/null 2>&1; then fail "a person obtained an executor token"; fi
item=e2e-request-app
owner /work '{"request_id":"e2e-request-app","repository":"e2e/demo","title":"Con la App","request":"Crea greeting.txt con un saludo."}' > /dev/null
sleep 3
wait_for proposed 60
grep -qx ghs_e2e_repo_demo "$work/gh.json.tokens" || fail "the executor did not use a token limited to the repository"
if grep -qx ghs_e2e "$work/gh.json.tokens"; then fail "the executor used a token for every repository"; fi
echo "The executor used a short-lived token for one repository"
