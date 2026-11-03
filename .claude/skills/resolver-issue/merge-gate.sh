#!/usr/bin/env bash
# Merges a PR only with every required check explicitly green on its current head, and by that SHA.
# Anything else stops: a check that cannot be read or has not started is not a pass.
set -euo pipefail
pr="${1:?Usage: merge-gate.sh <pr> <subject>}"
subject="${2:?Usage: merge-gate.sh <pr> <subject>}"
timeout="${MERGE_GATE_TIMEOUT:-2400}"
interval="${MERGE_GATE_INTERVAL:-30}"
required=("java (21)" "java (25)" "e2e" "container")

stop() { echo "#$pr NO se fusiona: $*" >&2; exit 1; }

head="$(gh pr view "$pr" --json headRefOid --jq .headRefOid)"
[[ "$head" =~ ^[0-9a-f]{40}$ ]] || stop "head ilegible"

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
