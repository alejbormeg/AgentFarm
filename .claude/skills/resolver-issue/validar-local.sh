#!/usr/bin/env bash
# Runs on one exact commit, in a clean checkout, what java.yml runs in Actions, and records that it passed.
# merge-gate.sh --local merges only the commit recorded here. Beside a live panel: its own ports and Compose project.
# Usage: validar-local.sh [--java25] [commit]. E2E_CHROME or google-chrome is required for the browser smoke.
set -euo pipefail
java25=0
[[ "${1:-}" != --java25 ]] || { java25=1; shift; }
repo="$(git rev-parse --show-toplevel)"
record="$(cd "$(git rev-parse --git-common-dir)" && pwd)/agentfarm-validated"
commit="$(git rev-parse --verify "${1:-HEAD}^{commit}")"
chrome="${E2E_CHROME:-$(command -v google-chrome || true)}"
[[ -n "$chrome" ]] || { echo "Sin Chrome no se valida: la CI corre el smoke de navegador. Consulta la skill panel-aislado." >&2; exit 2; }
: > "$record"

# Keep this list in step with paths-ignore in java.yml and merge-gate.sh.
git fetch -q origin main
changed="$(git diff --name-only "$(git merge-base origin/main "$commit")" "$commit")"
if ! grep -qvE '^(docs/.*|site/.*|[^/]+\.md)$' <<<"$changed"; then
  printf '%s\n' "$commit" > "$record"
  echo "Solo documentación: la CI no correría. Validado ${commit:0:7}."
  exit 0
fi

work="$(mktemp -d)"
tree="$work/tree"
project="agentfarm-validar-$$"
port="${VALIDAR_PORT:-18092}"
cleanup() {
  if [[ -f "$work/ports.yaml" ]]; then
    (cd "$tree" && COMPOSE_PROJECT_NAME="$project" COMPOSE_FILE="compose.yaml:$work/ports.yaml" docker compose down -v --remove-orphans) >/dev/null 2>&1 || true
  fi
  git -C "$repo" worktree remove --force "$tree" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
git worktree add -q --detach "$tree" "$commit"
cd "$tree"
step() { echo; echo "== $*"; "$@"; }

if [[ "$java25" == 1 ]]; then
  # Same steps that need a JDK, on Java 25. Runs as this user so permission tests mean the same.
  docker build -q -t agentfarm-validar-java25 - >/dev/null <<'DOCKER'
FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends git python3 >/dev/null
DOCKER
  mkdir -p "$HOME/.m2"
  step docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e MAVEN_USER_HOME=/m2 -e MVN=./mvnw \
    -v "$HOME/.m2:/m2" -v "$tree:$tree" -v "$repo/.git:$repo/.git" -w "$tree" agentfarm-validar-java25 \
    bash -c 'git config --global --add safe.directory "*"; bash runtime/mvn-retry.sh -B -ntp -q -Dmaven.repo.local=/m2/repository verify \
      && java -jar target/agentfarm.jar memory --file examples/project-memory.json --repository owner/example >/dev/null \
      && java -jar target/agentfarm.jar memory --file agentfarm-memory.json --repository alejbormeg/AgentFarm >/dev/null'
  git clean -qfdx
fi

echo; echo "#### java (21)"
step env MVN=./mvnw bash runtime/mvn-retry.sh -B -ntp -q verify
step bash tests/credentials.sh
step bash tests/ops.sh
step bash tests/merge_gate.sh
step bash tests/pre_push.sh
step python3 -I tests/pilot/test_run.py
step java -jar target/agentfarm.jar memory --file examples/project-memory.json --repository owner/example >/dev/null
step java -jar target/agentfarm.jar memory --file agentfarm-memory.json --repository alejbormeg/AgentFarm >/dev/null
step bash runtime/verify.sh
step bash runtime/package.sh
step bash tests/security_scan.sh

echo; echo "#### e2e"
step docker pull -q bash:5.2
step env E2E_CHROME="$chrome" E2E_PORT="${E2E_PORT:-18120}" bash tests/e2e/run.sh
step env AGENTFARM_DEMO_ONCE=1 bash runtime/demo.sh

echo; echo "#### container"
cat > "$work/ports.yaml" <<YAML
services:
  agentfarm:
    ports: !override
      - "127.0.0.1:$port:8080"
YAML
step env AGENTFARM_COMMIT="$commit" COMPOSE_PROJECT_NAME="$project" COMPOSE_FILE="compose.yaml:$work/ports.yaml" docker compose up --build --wait -d
step curl -fsS -o /dev/null "http://127.0.0.1:$port/login"
step env PANEL_URL="http://127.0.0.1:$port/login" python3 tests/container_http.py
step bash tests/dedicated_user.sh
step env BACKUP_TEST_PORT="$((port + 1))" bash tests/compose_backup.sh

printf '%s\n' "$commit" > "$record"
echo; echo "Validado ${commit:0:7}: pasa todo lo que correría la CI."
