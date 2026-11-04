#!/usr/bin/env bash
# The merge gate against a gh double: only explicit passes on the current head may merge.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
sha=0123456789012345678901234567890123456789
other=9876543210987654321098765432109876543210
green=$'java (21)\tcompleted\tsuccess\ne2e\tcompleted\tsuccess\ncontainer\tcompleted\tsuccess'

mkdir "$work/bin"
cat > "$work/bin/gh" <<'GH'
#!/usr/bin/env bash
# Answers from files in $FAKE: runs.N are consecutive check reads, heads the consecutive PR heads.
set -euo pipefail
case "$1 $2" in
  "pr view")
    case "$*" in
      *headRefOid*)
        n=$(cat "$FAKE/head_reads"); echo $((n + 1)) > "$FAKE/head_reads"
        sed -n "$((n + 1))p" "$FAKE/heads" | grep . || tail -1 "$FAKE/heads" ;;
      *mergeable*) cat "$FAKE/mergeable" ;;
      *state*) [[ -f "$FAKE/merged" ]] && echo MERGED || echo OPEN ;;
    esac ;;
  "api --paginate")
    [[ ! -f "$FAKE/api_fails" ]] || exit 1
    [[ "$3" != */files ]] || { cat "$FAKE/files"; exit 0; }
    n=$(cat "$FAKE/reads"); echo $((n + 1)) > "$FAKE/reads"
    if [[ -f "$FAKE/runs.$n" ]]; then cat "$FAKE/runs.$n"; else cat "$FAKE/runs.last"; fi ;;
  "api repos/{owner}/{repo}/actions/permissions")
    [[ ! -f "$FAKE/api_fails" ]] || exit 1
    cat "$FAKE/actions" ;;
  "pr merge") echo "$*" > "$FAKE/merged" ;;
  *) echo "unexpected gh $*" >&2; exit 2 ;;
esac
GH
chmod +x "$work/bin/gh"

scenario() {
  FAKE="$work/s"; rm -rf "$FAKE"; mkdir "$FAKE"
  echo 0 > "$FAKE/reads"; echo 0 > "$FAKE/head_reads"; echo "$sha" > "$FAKE/heads"; echo MERGEABLE > "$FAKE/mergeable"; echo src/Main.java > "$FAKE/files"
  printf '%s\n' "$1" > "$FAKE/runs.last"
}
gate() {
  FAKE="$FAKE" PATH="$work/bin:$PATH" MERGE_GATE_INTERVAL=0 MERGE_GATE_TIMEOUT="${MERGE_GATE_TIMEOUT:-0}" \
    bash "$root/.claude/skills/resolver-issue/merge-gate.sh" 7 "[#1] Título (#7)" > "$FAKE/out" 2>&1
}
merges() {
  gate || { cat "$FAKE/out" >&2; echo "FAIL: $1" >&2; exit 1; }
  grep -q -- "--match-head-commit $sha" "$FAKE/merged" || { echo "FAIL: $1: merged without the SHA" >&2; exit 1; }
  grep -q -- "--squash" "$FAKE/merged" || { echo "FAIL: $1: not squashed" >&2; exit 1; }
  echo "ok   $1"
}
refuses() {
  if gate || [[ -f "$FAKE/merged" ]]; then echo "FAIL: $1: it merged" >&2; exit 1; fi
  grep -q "NO se fusiona" "$FAKE/out" || { cat "$FAKE/out" >&2; echo "FAIL: $1: no reason given" >&2; exit 1; }
  echo "ok   $1"
}

scenario "$green"
merges "all required checks green"

scenario "$green"$'\nextra\tcompleted\tsuccess'
merges "an extra green check"

scenario "$green"
printf 'e2e\tin_progress\t\n' > "$FAKE/runs.0"
MERGE_GATE_TIMEOUT=10 merges "waits for a running check"

scenario "${green/e2e$'\t'completed$'\t'success/e2e$'\t'completed$'\t'failure}"
refuses "a failed check"

scenario "$green"$'\nlint\tcompleted\tskipped'
refuses "a skipped check is not a pass"

scenario "$green"$'\nlint\tcompleted\tcancelled'
refuses "a cancelled check"

scenario $'java (21)\tcompleted\tsuccess\ncontainer\tcompleted\tsuccess'
refuses "a required check that never reported"

scenario ""
refuses "no checks at all"

scenario "${green/e2e$'\t'completed$'\t'success/e2e$'\t'in_progress$'\t'}"
refuses "a check still running at the timeout"

scenario "$green"
touch "$FAKE/api_fails"
refuses "checks that cannot be read"

scenario "$green"
printf '%s\n%s\n' "$sha" "$other" > "$FAKE/heads"
refuses "a head that moved while waiting"

scenario "$green"
echo CONFLICTING > "$FAKE/mergeable"
refuses "a conflicting PR"

scenario ""
printf 'docs/GUIDE.md\nCHANGELOG.md\nsite/index.html\n' > "$FAKE/files"
merges "documentation needs no checks, as the CI ignores it"

scenario ""
printf 'docs/GUIDE.md\nsrc/main/resources/prompts/research.v5.md\n' > "$FAKE/files"
refuses "a prompt is not documentation, though it is Markdown"

scenario "docs\tcompleted\tfailure"
printf 'README.md\n' > "$FAKE/files"
refuses "documentation with a failed check"

scenario ""
: > "$FAKE/files"
refuses "a PR whose files cannot be listed"

# --local: no checks exist, the local validation of this exact head stands in for them while Actions is off.
git init -q "$work/repo"
validated="$work/repo/.git/agentfarm-validated"
local_gate() {
  (cd "$work/repo" && FAKE="$FAKE" PATH="$work/bin:$PATH" MERGE_GATE_INTERVAL=0 MERGE_GATE_TIMEOUT=0 \
    bash "$root/.claude/skills/resolver-issue/merge-gate.sh" --local 7 "[#1] Título (#7)") > "$FAKE/out" 2>&1
}
local_merges() {
  local_gate || { cat "$FAKE/out" >&2; echo "FAIL: $1" >&2; exit 1; }
  grep -q -- "--match-head-commit $sha" "$FAKE/merged" || { echo "FAIL: $1: merged without the SHA" >&2; exit 1; }
  echo "ok   $1"
}
local_refuses() {
  if local_gate || [[ -f "$FAKE/merged" ]]; then echo "FAIL: $1: it merged" >&2; exit 1; fi
  grep -q "NO se fusiona" "$FAKE/out" || { cat "$FAKE/out" >&2; echo "FAIL: $1: no reason given" >&2; exit 1; }
  echo "ok   $1"
}

scenario ""
echo false > "$FAKE/actions"; echo "$sha" > "$validated"
local_merges "--local with Actions off and this head validated"

scenario ""
echo false > "$FAKE/actions"; echo "$other" > "$validated"
local_refuses "--local when another commit was validated"

scenario ""
echo false > "$FAKE/actions"; rm -f "$validated"
local_refuses "--local with nothing validated"

scenario ""
echo true > "$FAKE/actions"; echo "$sha" > "$validated"
local_refuses "--local once Actions is back on"

scenario ""
touch "$FAKE/api_fails"; echo "$sha" > "$validated"
local_refuses "--local when the Actions state cannot be read"

scenario ""
echo false > "$FAKE/actions"; echo "$sha" > "$validated"; echo CONFLICTING > "$FAKE/mergeable"
local_refuses "--local on a conflicting PR"

scenario ""
if gate || [[ -f "$FAKE/merged" ]]; then echo "FAIL: validation without --local must not replace the checks" >&2; exit 1; fi
echo "ok   a validated head without --local still needs the checks"

echo "Merge gate checks passed"
