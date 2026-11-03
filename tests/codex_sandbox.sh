#!/usr/bin/env bash
# Checks the agents' profile against the real Codex sandbox, without inference. Needs Codex and the
# built JAR, so it is not part of CI: run it after updating Codex. It never reads a secret's content.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
state="$(mktemp -d "$HOME/.agentfarm-sandbox-XXXXXX")"
trap 'rm -rf "$state"' EXIT
checkout="$state/workspaces/probe"
mkdir -p "$checkout"
git -C "$checkout" init -q
echo "token" > "$state/node-token"
failures=0

profile() {
  java -jar "$root/target/agentfarm.jar" sandbox-profile --state "$state" --checkout "$checkout" --write "$1" --uid "$(id -u)"
}

# Turns the profile into Codex's -c options; $2 adds settings that widen it.
options() {
  python3 -I -c '
import json, sys
config = json.loads(sys.argv[1])
fs = config["permissions"]["agentfarm"]["filesystem"]
print("default_permissions=\"agentfarm\"")
print("permissions.agentfarm.filesystem={" + ",".join(json.dumps(k) + "=" + json.dumps(v) for k, v in fs.items()) + "}")
print("permissions.agentfarm.network.enabled=" + sys.argv[2])
' "$(profile "$1")" "$2"
}

probe='
import os, socket, sys
blocked = 0
for path in sys.argv[1:]:
    if not os.path.exists(path) and not os.path.islink(path):
        continue
    if path.endswith(".sock"):
        s = socket.socket(socket.AF_UNIX)
        s.settimeout(3)
        try:
            s.connect(path)
            print("ALCANZABLE", path)
            blocked = 1
        except OSError:
            pass
    elif os.access(path, os.R_OK):
        print("LEGIBLE", path)
        blocked = 1
sys.exit(blocked)
'

check() {
  local name="$1" write="$2" network="$3" args=()
  while IFS= read -r line; do args+=(-c "$line"); done < <(options "$write" "$network")
  if (cd "$checkout" && codex sandbox "${args[@]}" -- python3 -I -c "$probe" \
    /var/run/docker.sock /run/docker.sock /run/containerd/containerd.sock /run/podman/podman.sock \
    "/run/user/$(id -u)/bus" "/run/user/$(id -u)/docker.sock" \
    "$HOME/.config/gh/hosts.yml" "$HOME/.codex/auth.json" "$state/node-token"); then
    echo "ok   $name"
  else
    echo "FAIL $name"
    failures=$((failures + 1))
  fi
}

check "solo lectura, sin red" false false
check "escritura, sin red" true false
# A setting elsewhere could turn the network on: the hidden sockets must hold on their own.
check "escritura, con red" true true

result="$(cd "$checkout" && codex sandbox -c 'default_permissions="agentfarm"' \
  -c "permissions.agentfarm.filesystem={\":root\"=\"read\",\"$checkout\"=\"write\"}" \
  -- sh -c 'echo x > ok.txt && (echo x > .git/hooks/pre-commit) 2>/dev/null && echo HOOK || echo hook-bloqueado')"
if [[ "$result" == hook-bloqueado ]]; then echo "ok   .git protegido"; else echo "FAIL .git: $result"; failures=$((failures + 1)); fi

[[ "$failures" == 0 ]] || { echo "Sandbox FAILED: $failures"; exit 1; }
echo "Sandbox OK"
