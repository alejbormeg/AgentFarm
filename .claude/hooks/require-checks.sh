#!/usr/bin/env bash
# Blocks `git push` from this repository until comprobar.sh has passed on the exact commit.
# Most CI failure emails came from pushing what had never been run locally.
set -uo pipefail
input="$(cat)"
project="${CLAUDE_PROJECT_DIR:-$(pwd)}"
verdict="$(python3 -I -c '
import json, os, re, shlex, sys
try:
    command = json.loads(sys.argv[1])["tool_input"]["command"]
except (KeyError, TypeError, ValueError):
    print("allow"); sys.exit()
project = os.path.realpath(sys.argv[2])
pushes = False
elsewhere = False
for part in re.split(r"&&|\|\||;|\n", command):
    try:
        words = shlex.split(part, comments=True)
    except ValueError:
        words = part.split()
    if words[:1] == ["cd"] and len(words) > 1:
        elsewhere = os.path.realpath(os.path.expanduser(words[1])) != project
    if words[:1] == ["git"]:
        rest = words[1:]
        target = None
        while rest[:1] == ["-C"] or (rest and rest[0].startswith("-") and rest[0] != "--"):
            if rest[0] == "-C" and len(rest) > 1:
                target = rest[1]
                rest = rest[2:]
            elif rest[0] == "-c":
                rest = rest[2:]
            else:
                rest = rest[1:]
        if rest[:1] == ["push"]:
            away = os.path.realpath(os.path.expanduser(target)) != project if target else elsewhere
            pushes = pushes or not away
print("check" if pushes else "allow")
' "$input" "$project")"
[[ "$verdict" == check ]] || exit 0
git_dir="$(git -C "$project" rev-parse --git-dir 2>/dev/null)" || exit 0
[[ "$git_dir" = /* ]] || git_dir="$project/$git_dir"
head="$(git -C "$project" rev-parse HEAD)"
[[ "$(cat "$git_dir/agentfarm-checked" 2>/dev/null)" == "$head" ]] && exit 0
echo "Push bloqueado: ${head:0:7} no ha pasado las comprobaciones locales. Ejecuta: bash .claude/skills/resolver-issue/comprobar.sh" >&2
exit 2
