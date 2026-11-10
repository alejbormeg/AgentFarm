#!/usr/bin/env bash
# The installer served by a real panel, end to end, in a throwaway HOME: systemctl, Codex, Docker
# and gh are doubles, so nothing on this machine changes. Needs target/agentfarm.jar and
# target/agentfarm-executor-linux.tar.gz (bash runtime/package.sh).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
panel_pid=""
cleanup() { [[ -z "$panel_pid" ]] || kill "$panel_pid" 2>/dev/null || true; rm -rf "$work"; }
trap cleanup EXIT
fail() { echo "EXECUTOR INSTALL FAILED: $*" >&2; [[ ! -f "$work/panel.log" ]] || tail -20 "$work/panel.log" >&2; exit 1; }
for f in target/agentfarm.jar target/agentfarm-executor-linux.tar.gz; do
  [[ -f "$root/$f" ]] || fail "missing $f: run ./mvnw package and bash runtime/package.sh"
done

port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')"
panel="http://127.0.0.1:$port"
password="install-test-$(date +%s)-password"
AGENTFARM_PASSWORD="$password" AGENTFARM_DB="$work/panel/agentfarm.db" PORT="$port" \
  AGENTFARM_PANEL_URL="$panel" AGENTFARM_EXECUTOR_PACKAGE="$root/target/agentfarm-executor-linux.tar.gz" \
  java -jar "$root/target/agentfarm.jar" >"$work/panel.log" 2>&1 &
panel_pid=$!
for _ in $(seq 1 60); do curl -fs -o /dev/null "$panel/api/health" && break; sleep 1; done
curl -fs -o /dev/null "$panel/api/health" || fail "panel did not start"

jar="$work/cookies"
csrf="$(curl -fs -u "admin:$password" -c "$jar" -b "$jar" "$panel/api/csrf" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
owner() { curl -fsS -u "admin:$password" -c "$jar" -b "$jar" -H "X-CSRF-TOKEN: $csrf" -H 'Content-Type: application/json' -X POST "$panel/api$1" -d "$2"; }
owner /budget/configure '{"account":"install-account","mode":"subscription","shares":{"weekly":9800}}' >/dev/null
owner /nodes '{"id":"install-node","role":"napoleon"}' >/dev/null
owner /workers '{"id":"install-node-boxer","node":"install-node"}' >/dev/null
owner /workers '{"id":"install-node-reviewer","node":"install-node"}' >/dev/null
token="$(owner /executors '{"node":"install-node","account":"install-account"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
(umask 077 && printf '%s\n' "$token" >"$work/node-token")

# Doubles: everything this machine would change or need a session for.
export HOME="$work/home"
unset XDG_CONFIG_HOME XDG_DATA_HOME XDG_STATE_HOME
mkdir -p "$HOME" "$work/bin"
printf '#!/usr/bin/env bash\necho "$*" >> "%s"\n' "$work/systemctl.log" >"$work/bin/systemctl"
printf '#!/usr/bin/env bash\nexit 0\n' >"$work/bin/codex"
printf '#!/usr/bin/env bash\nexit 0\n' >"$work/bin/docker"
printf '#!/usr/bin/env bash\nexit 0\n' >"$work/bin/gh"
chmod +x "$work/bin/"*
export PATH="$work/bin:$PATH"
curl -fsS -o "$work/install.sh" "$panel/executor/install.sh"
grep -q "^panel='$panel'$" "$work/install.sh" || fail "the script does not carry the panel's address"

out="$(bash "$work/install.sh" --token-file "$work/node-token" 2>&1)" || fail "installer failed: $out"
env_file="$HOME/.config/agentfarm/executor.env"
for line in AGENTFARM_NODE=install-node AGENTFARM_ACCOUNT=install-account "AGENTFARM_URL=$panel" \
  AGENTFARM_IMPLEMENTER=install-node-boxer AGENTFARM_REVIEWER=install-node-reviewer \
  "AGENTFARM_STATE=$HOME/.local/state/agentfarm"; do
  grep -qx "$line" "$env_file" || fail "executor.env lacks $line"
done
[[ "$(stat -c %a "$env_file")" == 600 ]] || fail "executor.env is not private"
[[ "$(stat -c %a "$HOME/.local/state/agentfarm/node-token")" == 600 ]] || fail "node-token is not private"
[[ "$(cat "$HOME/.local/state/agentfarm/node-token")" == "$token" ]] || fail "node-token differs"
[[ "$(stat -c %a "$HOME/.local/state/agentfarm")" == 700 ]] || fail "state directory is not private"
unit="$HOME/.config/systemd/user/agentfarm-executor.service"
grep -q "ExecStart=\"$HOME/.local/share/agentfarm/executor\.[0-9TZ]*/agentfarm-executor/executor\"" "$unit" ||
  fail "unit does not start the installed executor"
grep -q 'restart agentfarm-executor.service' "$work/systemctl.log" || fail "service not started (double)"
grep -q "$token" <<<"$out" && fail "the installer printed the credential"
grep -q 'Bearer \$token' "$work/install.sh" && fail "the credential goes to curl as an argument"

# An executor already there stays unless asked.
if out="$(bash "$work/install.sh" --token-file "$work/node-token" 2>&1)"; then fail "replaced without --replace"; fi
[[ "$out" == *"--replace"* ]] || fail "no hint about --replace: $out"
sleep 1
bash "$work/install.sh" --token-file "$work/node-token" --replace >/dev/null 2>&1 || fail "--replace failed"
[[ "$(find "$HOME/.local/share/agentfarm" -maxdepth 1 -name 'executor.*' | wc -l)" == 2 ]] ||
  fail "expected the new copy and the previous one"

# A wrong or revoked credential stops before anything is written.
printf 'afn_%s\n' "$(printf 'x%.0s' $(seq 1 43))" >"$work/bad-token"
if out="$(bash "$work/install.sh" --token-file "$work/bad-token" --replace 2>&1)"; then fail "accepted an unknown credential"; fi
[[ "$out" == *"no reconoce"* ]] || fail "unclear message for an unknown credential: $out"

# Without systemd it installs the files and says how to run it.
printf '#!/usr/bin/env bash\n[[ "$*" == *show-environment* ]] && exit 1\necho "$*" >> "%s"\n' "$work/systemctl.log" >"$work/bin/systemctl"
: >"$work/systemctl.log"
out="$(bash "$work/install.sh" --token-file "$work/node-token" --replace 2>&1)" || fail "failed without systemd: $out"
[[ "$out" == *"systemd=true"* ]] || fail "no systemd hint: $out"
[[ ! -s "$work/systemctl.log" ]] || fail "touched systemd without it"
echo "Executor install OK"
