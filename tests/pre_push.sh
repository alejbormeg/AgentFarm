#!/usr/bin/env bash
# The hook that stops a push of a commit that never passed the local checks.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
hook="$root/.claude/hooks/require-checks.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git init -q -b main "$work/repo"
git -C "$work/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m init
mkdir "$work/other"
run() {
  python3 -I -c 'import json,sys;print(json.dumps({"tool_name":"Bash","tool_input":{"command":sys.argv[1]}}))' "$1" \
    | CLAUDE_PROJECT_DIR="$work/repo" bash "$hook" 2>"$work/err"
}
blocks() { if run "$1"; then echo "FAIL: not blocked: $1" >&2; exit 1; fi; grep -q comprobar.sh "$work/err" || { echo "FAIL: no explanation: $1" >&2; exit 1; }; echo "ok   blocks: $1"; }
allows() { run "$1" || { echo "FAIL: blocked: $1" >&2; exit 1; }; echo "ok   allows: $1"; }

allows "git status"
allows "git log --grep=push"
allows "echo git push"
allows "gh pr merge 7 --squash"
allows "cd $work/other && git push"
allows "git -C $work/other push origin x"
blocks "git push"
blocks "git push -u origin rama"
blocks "cd $work/repo && git push"
blocks "git -C $work/repo push"
blocks "./mvnw verify; git push -q 2>&1 | grep -v remote"
git -C "$work/repo" rev-parse HEAD > "$work/repo/.git/agentfarm-checked"
allows "git push"
git -C "$work/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m next
blocks "git push"
echo "Pre-push checks passed"
