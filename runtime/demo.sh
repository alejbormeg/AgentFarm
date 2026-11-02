#!/usr/bin/env bash
# A complete farm on this machine with simulated agents and a simulated GitHub: no accounts,
# no quota, nothing leaves the computer. Needs JDK 21+, git and python3.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
port="${AGENTFARM_DEMO_PORT:-8090}"
panel="http://127.0.0.1:$port"
for tool in java javac git python3 curl; do
  command -v "$tool" >/dev/null || { echo "La demo necesita $tool (JDK 21+, git y python3)." >&2; exit 1; }
done
if curl -fs -o /dev/null "$panel/api/health"; then
  echo "El puerto $port está ocupado. Usa otro con AGENTFARM_DEMO_PORT." >&2
  exit 1
fi
if [[ ! -f "$root/target/agentfarm.jar" ]]; then
  echo "Compilando AgentFarm (solo la primera vez)..."
  (cd "$root" && ./mvnw -B -ntp -q -DskipTests package)
fi

work="$(mktemp -d)"
password="demo-$(python3 -c 'import secrets;print(secrets.token_urlsafe(12))')"
pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT

echo "Preparando un repositorio de ejemplo y arrancando el panel..."
git init -q -b main "$work/seed"
echo "# Tienda de ejemplo" > "$work/seed/README.md"
mkdir -p "$work/seed/demo"
echo "inicio" > "$work/seed/demo/inicio.txt"
cat > "$work/seed/agentfarm.yaml" <<'YAML'
version: 1
repository: demo/tienda
setup: ""
tests:
  unit: test -d demo
  integration: test -n "$(ls demo)"
allowed_paths: [demo/, tests/, .github/workflows/]
environment: local
review: independent
secret_refs: {}
quality:
  checks:
    lint: "true"
YAML
git -C "$work/seed" add -A
git -C "$work/seed" -c user.name=demo -c user.email=demo@localhost commit -q -m inicio
git clone -q --bare "$work/seed" "$work/remote.git"

AGENTFARM_DEMO=true AGENTFARM_PASSWORD="$password" AGENTFARM_DB="$work/panel/agentfarm.db" PORT="$port" \
  java -jar "$root/target/agentfarm.jar" > "$work/panel.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do curl -fs -o /dev/null "$panel/api/health" && break; sleep 1; done
curl -fs -o /dev/null "$panel/api/health" || { tail -20 "$work/panel.log" >&2; exit 1; }

jar="$work/cookies"
csrf=$(curl -fs -u "admin:$password" -c "$jar" -b "$jar" "$panel/api/csrf" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')
owner() { curl -fsS -u "admin:$password" -c "$jar" -b "$jar" -H "X-CSRF-TOKEN: $csrf" -H 'Content-Type: application/json' -X POST "$panel/api$1" -d "$2"; }
owner /budget/configure '{"account":"demo","mode":"subscription","shares":{"weekly":9800}}' > /dev/null
owner /nodes '{"id":"napoleon-demo","role":"napoleon"}' > /dev/null
owner /workers '{"id":"napoleon-demo-boxer","node":"napoleon-demo"}' > /dev/null
owner /workers '{"id":"napoleon-demo-reviewer","node":"napoleon-demo"}' > /dev/null
mkdir -p "$work/state"
owner /executors '{"node":"napoleon-demo","account":"demo"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])' > "$work/state/node-token"
chmod 600 "$work/state/node-token"

cp="$root/target/classes:$(find "$HOME/.m2/repository/com/fasterxml/jackson/core" "$HOME/.m2/repository/org/yaml/snakeyaml" -name '*.jar' | paste -sd:)"
mkdir -p "$work/classes"
javac -nowarn -cp "$cp" -d "$work/classes" "$root/runtime/CodexRunner.java" "$root/runtime/FarmDaemon.java" 2>/dev/null
E2E_GH_STATE="$work/gh.json" E2E_REMOTE="$work/remote.git" \
  java -cp "$work/classes:$cp" \
  -Dagentfarm.url="$panel" -Dagentfarm.state="$work/state" \
  -Dagentfarm.node=napoleon-demo -Dagentfarm.account=demo \
  -Dagentfarm.codex="$root/tests/e2e/fake-codex" -Dagentfarm.gh="$root/tests/e2e/fake_gh.py" \
  -Dagentfarm.sandbox=host FarmDaemon > "$work/executor.log" 2>&1 &
pids+=($!)

state() { curl -fs -u "admin:$password" "$panel/api/work" | python3 -c 'import json,sys;print([x for x in json.load(sys.stdin) if x["id"]==sys.argv[1]][0]["state"])' "$1"; }
wait_for() {
  for _ in $(seq 1 90); do
    [[ "$(state "$1")" == "$2" ]] && return 0
    [[ "$(state "$1")" == blocked ]] && { tail -5 "$work/executor.log" >&2; echo "La tarea $1 se bloqueó." >&2; exit 1; }
    sleep 1
  done
  echo "Tiempo agotado esperando $1." >&2
  exit 1
}
task() { # id, recipe (or -), title, request, whether to implement it
  local recipe=""
  [[ "$2" != - ]] && recipe=",\"recipe\":{\"id\":\"$2\",\"version\":1}"
  owner /work "{\"request_id\":\"$1\",\"repository\":\"demo/tienda\",\"title\":\"$3\",\"request\":\"$4\"$recipe}" > /dev/null
  wait_for "$1" proposed
  if [[ "$5" == yes ]]; then
    owner "/work/$1/actions/implement" '{"version":1}' > /dev/null
    wait_for "$1" review
  fi
  echo "  $3: listo"
}

echo "Los agentes simulados están trabajando (menos de un minuto)..."
task demo-dependency-0001 dependency-update "Actualizar jackson-databind" "Actualiza jackson-databind por la alerta de Dependabot." yes
task demo-tests-000000002 test-improvement "Probar contraseñas caducadas" "Las pruebas de login no cubren contraseñas caducadas." yes
task demo-bugfix-00000003 bugfix "Corregir el cupón caducado" "El cupón caducado se sigue aplicando en el carrito." yes
task demo-readme-00000004 - "Corregir un enlace del README" "Corrige el enlace roto del README." yes
task demo-latency-0000005 - "Añadir métricas de latencia" "Añade métricas de latencia al gateway." no
owner /initiatives '{"request_id":"demo-molino-00001","title":"Migrar a la API v2","goal":"Pasar la tienda a la API v2 sin cortar el servicio.","review_limit":2,"milestones":[{"id":"M1","title":"Cliente nuevo"},{"id":"M2","title":"Retirar la API v1"}]}' \
  | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["id"],d["version"])' > "$work/molino"
read -r molino version < "$work/molino"
owner "/initiatives/$molino/tasks" "{\"version\":$version,\"item\":\"demo-latency-0000005\",\"milestone\":\"M1\",\"depends_on\":[]}" > /dev/null
python3 "$root/runtime/demo_seed.py" "$work/panel/agentfarm.db" "$panel" "$password"

ready=$(curl -fs -u "admin:$password" "$panel/api/work/digest" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["ready"]))')
[[ "$ready" == 2 ]] || { echo "La demo no preparó las tareas esperadas ($ready)." >&2; exit 1; }
if [[ "${AGENTFARM_DEMO_ONCE:-}" == 1 ]]; then
  echo "Demo OK: $ready listas para integrar"
  exit 0
fi

cat <<TEXT

AgentFarm en modo demo: $panel
Usuario:    admin
Contraseña: $password

Los agentes y GitHub son simulados: nada sale de este equipo y nada se integra.
Abre la Bandeja, una tarea en revisión y Gobierno. Ctrl+C borra la demo.
TEXT
wait "${pids[0]}"
