#!/usr/bin/env bash
# Merges a PR only with every required check explicitly green on its current head, and by that SHA.
# Anything else stops: a check that cannot be read or has not started is not a pass.
# --local is the owner's decision while there are no Actions minutes: validar-local.sh must have passed on this
# exact head instead. It stops working as soon as Actions is back on, so the checks return without a change here.
set -euo pipefail
local_mode=0
[[ "${1:-}" != --local ]] || { local_mode=1; shift; }
pr="${1:?Usage: merge-gate.sh [--local] <pr> <subject>}"
subject="${2:?Usage: merge-gate.sh [--local] <pr> <subject>}"
timeout="${MERGE_GATE_TIMEOUT:-2400}"
interval="${MERGE_GATE_INTERVAL:-30}"
required=("java (21)" "e2e" "container")

stop() { echo "#$pr NO se fusiona: $*" >&2; exit 1; }

head="$(gh pr view "$pr" --json headRefOid --jq .headRefOid)"
[[ "$head" =~ ^[0-9a-f]{40}$ ]] || stop "head ilegible"

if [[ "$local_mode" == 1 ]]; then
  enabled="$(gh api 'repos/{owner}/{repo}/actions/permissions' --jq .enabled)" || stop "no se puede leer si Actions está activo"
  [[ "$enabled" == false ]] || stop "Actions está activo ($enabled): valen las comprobaciones de la CI, no --local"
  validated="$(cat "$(git rev-parse --git-common-dir)/agentfarm-validated" 2>/dev/null || true)"
  [[ "$validated" == "$head" ]] || stop "validar-local.sh no ha pasado sobre ${head:0:7}"
else
  # java.yml ignores these paths, so no check will ever report for a documentation-only PR. Keep them in step.
  files="$(gh api --paginate "repos/{owner}/{repo}/pulls/$pr/files" --jq '.[].filename')" || stop "no se pueden leer los archivos"
  [[ -n "$files" ]] || stop "la PR no cambia archivos"
  grep -qvE '^(docs/.*|site/.*|[^/]+\.md)$' <<<"$files" || required=()

  waited=0
  while :; do
    runs="$(gh api --paginate "repos/{owner}/{repo}/commits/$head/check-runs" \
      --jq '.check_runs[] | [.name, .status, (.conclusion // "")] | @tsv')" || stop "no se pueden leer las comprobaciones"
    failed="$(awk -F'\t' '$2 == "completed" && $3 != "success"' <<<"$runs")"
    [[ -z "$failed" ]] || stop "comprobaciones sin pasar en ${head:0:7}: $(cut -f1,3 <<<"$failed" | tr '\t\n' ' ')"
    pending="$(awk -F'\t' 'NF && $2 != "completed"' <<<"$runs")"
    missing=""
    for name in "${required[@]}"; do
      cut -f1 <<<"$runs" | grep -qxF "$name" || missing+="$name "
    done
    [[ -n "$pending" || -n "$missing" ]] || break
    (( waited < timeout )) || stop "se agotó la espera; pendientes: $(cut -f1 <<<"$pending" | tr '\n' ' ') ausentes: $missing"
    sleep "$interval"
    waited=$((waited + interval))
  done
fi

[[ "$(gh pr view "$pr" --json headRefOid --jq .headRefOid)" == "$head" ]] || stop "el head cambió durante la espera"
state=UNKNOWN
for _ in 1 2 3 4 5 6; do
  state="$(gh pr view "$pr" --json mergeable --jq .mergeable)"
  [[ "$state" == UNKNOWN ]] || break
  sleep "$((interval < 5 ? interval : 5))"
done
[[ "$state" == MERGEABLE ]] || stop "estado de fusión $state"

gh pr merge "$pr" --squash --delete-branch --match-head-commit "$head" --subject "$subject" >/dev/null \
  || stop "falló la fusión"
[[ "$(gh pr view "$pr" --json state --jq .state)" == MERGED ]] || stop "la PR no consta como fusionada"
echo "#$pr fusionada en ${head:0:7}"
