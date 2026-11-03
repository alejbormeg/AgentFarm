#!/usr/bin/env bash
# The dedicated-user installer in a throwaway Ubuntu container: what it can check without systemd.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/agentfarm-executor"
echo '#!/bin/sh' > "$work/agentfarm-executor/executor"
tar -czf "$work/package.tar.gz" -C "$work" agentfarm-executor
cat > "$work/check.sh" <<'CHECK'
set -u
fail() { echo "FAIL: $*" >&2; exit 1; }
script=/test/install-dedicated-user.sh
useradd --create-home --shell /bin/bash farmer
echo secret > /home/farmer/credential
chmod 755 /home/farmer

bash "$script" /missing.tar.gz farmer >/dev/null 2>&1 && fail "accepted a missing package"
bash "$script" /test/package.tar.gz farmer farmer >/dev/null 2>&1 && fail "accepted the farmer as the user"
su -s /bin/sh farmer -c "bash $script /test/package.tar.gz" >/dev/null 2>&1 && fail "ran without root"

out="$(bash "$script" /test/package.tar.gz farmer 2>&1)" && fail "an open farmer home must be reported"
grep -q "puede leer /home/farmer" <<<"$out" || fail "did not name the open home: $out"
[ "$(stat -c %a /home/agentfarm)" = 700 ] || fail "home is not private"
[ "$(stat -c %U /home/agentfarm/.local/state/agentfarm)" = agentfarm ] || fail "state is not the user's"
ls /home/agentfarm/.local/lib/agentfarm/executor.*/agentfarm-executor/executor >/dev/null || fail "package not extracted"
id -nG agentfarm | grep -qwE 'sudo|docker|adm' && fail "privileged group"

chmod 750 /home/farmer
out="$(bash "$script" /test/package.tar.gz farmer 2>&1)" || fail "a closed home must pass: $out"
grep -q "no puede leer la home de farmer" <<<"$out" || fail "did not confirm: $out"
su -s /bin/sh agentfarm -c 'cat /home/farmer/credential' >/dev/null 2>&1 && fail "the user read the farmer's file"
grep -q "gh auth login" <<<"$out" || fail "next steps are missing"

usermod -aG adm agentfarm
bash "$script" /test/package.tar.gz farmer >/dev/null 2>&1 && fail "accepted a privileged user"
echo "Dedicated user checks passed"
CHECK
cp "$root/runtime/install-dedicated-user.sh" "$work/"
docker run --rm -v "$work:/test:ro" ubuntu:24.04 bash /test/check.sh
