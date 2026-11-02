#!/usr/bin/env bash
# Runs the reference tasks with the real agent and doubled GitHub. Spends quota unless EVAL_CODEX
# is a double. Usage: EVAL_CODEX=codex bash tests/eval/run.sh on|off
set -euo pipefail
mode="${1:?on or off}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
eval="$root/tests/eval"
work="$(mktemp -d)"
port="${EVAL_PORT:-18091}"
panel="http://127.0.0.1:$port"
password="eval-password-$(date +%s)-xyz"
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$work"; }
trap cleanup EXIT

cp -r "$eval/fixture" "$work/seed"
environment="environment: container
container: {image: 'python:3.12-slim', network: setup, timeout_minutes: 10}"
cat > "$work/seed/agentfarm.yaml" <<YAML
version: 1
repository: eval/shop
setup: ""
tests:
  unit: python3 -m unittest discover -s tests -t .
  integration: python3 -m tests.integration
allowed_paths: [shop.py, tests/, .github/workflows/]
${EVAL_ENVIRONMENT:-$environment}
review: independent
secret_refs: {}
quality:
  metrics:
    mutation: python3 tools/mutate.py
YAML
git init -q -b main "$work/seed"
git -C "$work/seed" add -A
git -C "$work/seed" -c user.name=eval -c user.email=eval@localhost commit -q -m seed
git clone -q --bare "$work/seed" "$work/remote.git"

AGENTFARM_PASSWORD="$password" AGENTFARM_DB="$work/panel/agentfarm.db" PORT="$port" \
  java -jar "$root/target/agentfarm.jar" > "$work/panel.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do curl -fs -o /dev/null "$panel/api/health" && break; sleep 1; done

jar="$work/cookies"
csrf=$(curl -fs -u "admin:$password" -c "$jar" -b "$jar" "$panel/api/csrf" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
owner() { curl -fsS -u "admin:$password" -c "$jar" -b "$jar" -H "X-CSRF-TOKEN: $csrf" -H 'Content-Type: application/json' -X POST "$panel/api$1" -d "$2"; }
owner /budget/configure '{"account":"eval-sub","mode":"subscription","shares":{"weekly":10000}}' > /dev/null
owner /nodes '{"id":"eval-node","role":"napoleon"}' > /dev/null
owner /workers '{"id":"eval-node-boxer","node":"eval-node"}' > /dev/null
owner /workers '{"id":"eval-node-reviewer","node":"eval-node"}' > /dev/null
mkdir -p "$work/state"
owner /executors '{"node":"eval-node","account":"eval-sub"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])' > "$work/state/node-token"
chmod 600 "$work/state/node-token"

cp="$root/target/classes:$(find "$HOME/.m2/repository/com/fasterxml/jackson/core" "$HOME/.m2/repository/org/yaml/snakeyaml" -name '*.jar' | paste -sd:)"
mkdir -p "$work/classes"
javac -nowarn -cp "$cp" -d "$work/classes" "$root/runtime/CodexRunner.java" "$root/runtime/FarmDaemon.java"
E2E_GH_STATE="$work/gh.json" E2E_REMOTE="$work/remote.git" \
  java -cp "$work/classes:$cp" \
  -Dagentfarm.url="$panel" -Dagentfarm.state="$work/state" \
  -Dagentfarm.node=eval-node -Dagentfarm.account=eval-sub \
  -Dagentfarm.codex="${EVAL_CODEX:?set EVAL_CODEX}" -Dagentfarm.gh="$root/tests/e2e/fake_gh.py" \
  -Dagentfarm.skills="$mode" -Dagentfarm.sandbox="${EVAL_SANDBOX:-container}" \
  FarmDaemon > "$work/executor.log" 2>&1 &
pids+=($!)

state() { curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print([x for x in json.load(sys.stdin) if x["id"]==sys.argv[1]][0]["state"])' "$1"; }
wait_for() {
  for _ in $(seq 1 "${EVAL_WAIT:-900}"); do
    s=$(state "$1"); [[ "$s" == "$2" ]] && return 0
    if [[ "$s" == blocked ]]; then
      echo "$1 blocked: $(curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print([x for x in json.load(sys.stdin) if x["id"]==sys.argv[1]][0]["error"])' "$1")" >&2
      return 1
    fi
    sleep 2
  done
  echo "timeout: $1 waiting for $2" >&2; return 1
}
python3 -c 'import json,sys;[print(json.dumps(dict(t,request_id=t.pop("id"),repository="eval/shop"))) for t in json.load(open(sys.argv[1]))]' "$eval/tasks.json" > "$work/tasks.ndjson"
while read -r task; do
  id=$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["request_id"])' "$task")
  echo "Running $id with skills $mode"
  owner /work "$task" > /dev/null
  if wait_for "$id" proposed; then
    version=$(curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print([x for x in json.load(sys.stdin) if x["id"]==sys.argv[1]][0]["version"])' "$id")
    owner "/work/$id/actions/implement" "{\"version\":$version}" > /dev/null
    wait_for "$id" review || true
  fi
done < "$work/tasks.ndjson"

mkdir -p "$eval/results"
curl -fs -u "admin:$password" "$panel/api/work" > "$eval/results/$mode.json"
echo "Results in tests/eval/results/$mode.json"
