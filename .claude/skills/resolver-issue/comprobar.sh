#!/usr/bin/env bash
# Runs locally what CI would run for this branch's changes, and records that this commit passed.
# Usage: comprobar.sh [base], from a clean tree. E2E_CHROME or google-chrome enables the browser smoke.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
cd "$root"
base="${1:-origin/main}"
[[ -z "$(git status --porcelain)" ]] || { echo "Haz commit antes: se comprueba el commit, no el árbol de trabajo." >&2; exit 2; }
git_dir="$(git rev-parse --absolute-git-dir)"
head="$(git rev-parse HEAD)"
# validar-local.sh runs all of this and more, in a clean checkout: no need to run it twice for one commit.
if [[ "$(cat "$(git rev-parse --git-common-dir)/agentfarm-validated" 2>/dev/null || true)" == "$head" ]]; then
  printf '%s\n' "$head" > "$git_dir/agentfarm-checked"
  echo "Comprobado ${head:0:7}: validar-local.sh ya pasó sobre este commit."
  exit 0
fi
changed="$(git diff --name-only "$(git merge-base "$base" HEAD)" HEAD)"
touches() { grep -qE "$1" <<<"$changed"; }
step() { echo; echo "== $*"; "$@"; }

while IFS= read -r file; do
  [[ -f "$file" ]] || continue
  case "$file" in
    *.sh | agentfarm) bash -n "$file" ;;
    *.mjs | *.js) [[ "$file" == *vendor* ]] || node --check "$file" ;;
    *.py) python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$file" ;;
  esac
done <<<"$changed"
echo "Sintaxis OK"

# Every script a workflow calls must exist: a typo only shows up in CI otherwise.
for script in $(grep -hoE '(tests|runtime)/[A-Za-z0-9_./-]+\.(sh|py)' .github/workflows/*.yml | sort -u); do
  [[ -f "$script" ]] || { echo "Un workflow llama a $script, que no existe." >&2; exit 1; }
done

java_changed=0
if touches '^(src/|pom\.xml|\.mvn/|runtime/.*\.java|examples/|agentfarm-memory\.json)'; then
  java_changed=1
  step ./mvnw -B -ntp -q verify
  step java -jar target/agentfarm.jar memory --file examples/project-memory.json --repository owner/example >/dev/null
  step java -jar target/agentfarm.jar memory --file agentfarm-memory.json --repository alejbormeg/AgentFarm >/dev/null
fi
if touches '^(agentfarm$|tests/(credentials|ops)\.sh|src/main/java/io/agentfarm/(Cli|core/GitHub))'; then
  [[ "$java_changed" == 1 || -f target/agentfarm.jar ]] || ./mvnw -B -ntp -q -DskipTests package
  step bash tests/credentials.sh
  step bash tests/ops.sh
fi
touches 'merge-gate\.sh|tests/merge_gate\.sh' && step bash tests/merge_gate.sh
touches '^tests/pilot/' && step python3 -I tests/pilot/test_run.py
touches 'require-checks\.sh|comprobar\.sh|tests/pre_push\.sh' && step bash tests/pre_push.sh
touches 'install-dedicated-user\.sh|tests/dedicated_user\.sh' && step bash tests/dedicated_user.sh

if touches '^(src/main/|runtime/|tests/e2e/|tests/ui/|pom\.xml)'; then
  [[ "$java_changed" == 1 ]] || ./mvnw -B -ntp -q -DskipTests package
  step bash runtime/verify.sh
  chrome="${E2E_CHROME:-$(command -v google-chrome || true)}"
  [[ -n "$chrome" ]] || echo "AVISO: sin Chrome, el smoke de navegador solo correrá en la CI. Consulta la skill panel-aislado."
  step env E2E_CHROME="$chrome" E2E_PORT="${E2E_PORT:-18120}" bash tests/e2e/run.sh
fi
# The container job restarts the panel: the only place a migration that breaks a restart shows up.
if touches '^(Dockerfile|compose\.yaml|agentfarm$|tests/compose_backup\.sh|src/main/java/io/agentfarm/core/(Store|SchemaMigrations|Backups)\.java)'; then
  step env AGENTFARM_COMMIT="$head" docker compose build
  step bash tests/compose_backup.sh
fi

printf '%s\n' "$head" > "$git_dir/agentfarm-checked"
echo; echo "Comprobado ${head:0:7}: ya se puede publicar."
