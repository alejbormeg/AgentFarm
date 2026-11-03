#!/usr/bin/env bash
# ./agentfarm github-token and github-app, with a Docker double: the secret must never be shown.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail() { echo "CREDENTIALS FAILED: $*" >&2; exit 1; }
copy="$work/copy"
mkdir -p "$copy" "$work/bin"
cp "$root/agentfarm" "$copy/"
cat > "$work/bin/docker" <<SH
#!/usr/bin/env bash
echo "\$*" >> "$work/docker.log"
[[ "\$*" == "compose version" ]] && exit 0
if [[ "\$*" == *"cat > /data/"* ]]; then cat > "$work/stored"; fi
exit 0
SH
chmod +x "$work/bin/docker"
export PATH="$work/bin:$PATH"

out="$(printf 'ghp_secreto\n' | "$copy/agentfarm" github-token 2>&1)" || fail "github-token: $out"
[[ "$(cat "$work/stored")" == ghp_secreto ]] || fail "token not stored"
! grep -q secreto <<<"$out" || fail "token shown in the output"
! grep -q secreto "$work/docker.log" || fail "token in a command line"

if printf '\n' | "$copy/agentfarm" github-token > /dev/null 2>&1; then fail "accepted an empty token"; fi

echo "clave" > "$work/key.pem"
out="$("$copy/agentfarm" github-app 123 456 "$work/key.pem" 2>&1)" || fail "github-app: $out"
[[ "$(cat "$work/stored")" == clave ]] || fail "key not stored"
grep -qx 'AGENTFARM_GITHUB_APP_ID=123' "$copy/.env" || fail "app id"
grep -qx 'AGENTFARM_GITHUB_INSTALLATION_ID=456' "$copy/.env" || fail "installation id"
grep -qx 'AGENTFARM_GITHUB_APP_KEY_FILE=/data/github-app.pem' "$copy/.env" || fail "key file"
grep -q 'up -d --wait --no-build agentfarm' "$work/docker.log" || fail "panel not restarted"
"$copy/agentfarm" github-app 124 456 "$work/key.pem" > /dev/null
[[ "$(grep -c AGENTFARM_GITHUB_APP_ID "$copy/.env")" == 1 ]] || fail "duplicated settings"
if "$copy/agentfarm" github-app x 456 "$work/key.pem" > /dev/null 2>&1; then fail "accepted a bad id"; fi
echo "Credentials OK"
