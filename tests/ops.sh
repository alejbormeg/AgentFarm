#!/usr/bin/env bash
# Launcher, service installer and Maven retry, with doubles for systemctl and mvn.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail() { echo "OPS FAILED: $*" >&2; exit 1; }

# Without arguments the launcher explains itself and starts nothing.
for args in "" help --help; do
  out="$("$root/agentfarm" $args)" || fail "help exits with an error: '$args'"
  [[ "$out" == Uso:* ]] || fail "no usage for '$args': $out"
done

# The installer restarts the unit, and prunes old copies no unit uses.
export HOME="$work/home"
lib="$HOME/.local/lib/agentfarm"
mkdir -p "$work/bin" "$HOME/.config/systemd/user"
printf '#!/usr/bin/env bash\necho "$*" >> "%s"\n' "$work/systemctl.log" > "$work/bin/systemctl"
chmod +x "$work/bin/systemctl"
for name in oldest slot older previous current; do
  mkdir -p "$lib/executor.$name/agentfarm-executor"
  cp "$root/runtime/install-executor.sh" "$lib/executor.$name/agentfarm-executor/"
  sleep 0.05
done
echo "ExecStart=\"$lib/executor.slot/agentfarm-executor/executor\"" > "$HOME/.config/systemd/user/agentfarm-executor-s2.service"
touch "$work/executor.env"
PATH="$work/bin:$PATH" bash "$lib/executor.current/agentfarm-executor/install-executor.sh" "$work/executor.env"
grep -q 'restart agentfarm-executor.service' "$work/systemctl.log" || fail "service not restarted"
[[ -d "$lib/executor.current" && -d "$lib/executor.previous" ]] || fail "removed the copies to keep"
[[ -d "$lib/executor.slot" ]] || fail "removed a copy another unit uses"
[[ ! -e "$lib/executor.older" && ! -e "$lib/executor.oldest" ]] || fail "old copies remain"

# Maven repeats after a cut download, and not after a failing test.
cat > "$work/bin/fake-mvn" <<SH
#!/usr/bin/env bash
echo run >> "$work/mvn.log"
[[ "\$(wc -l < "$work/mvn.log")" -ge 2 ]] && exit 0
echo "\$MVN_MESSAGE"
exit 1
SH
chmod +x "$work/bin/fake-mvn"
export MVN="$work/bin/fake-mvn" MVN_RETRY_DELAY=0
MVN_MESSAGE="Could not transfer artifact a:b" bash "$root/runtime/mvn-retry.sh" verify > /dev/null || fail "no retry after a cut download"
[[ "$(wc -l < "$work/mvn.log")" == 2 ]] || fail "unexpected attempts"
rm "$work/mvn.log"
MVN_MESSAGE="wget: Failed to fetch https://repo.maven.apache.org/x.zip" bash "$root/runtime/mvn-retry.sh" verify > /dev/null 2>&1 \
  || fail "no retry after a cut Maven wrapper download"
[[ "$(wc -l < "$work/mvn.log")" == 2 ]] || fail "unexpected attempts"
rm "$work/mvn.log"
if MVN_MESSAGE="Tests run: 1, Failures: 1" bash "$root/runtime/mvn-retry.sh" verify > /dev/null; then fail "a failing test passed"; fi
[[ "$(wc -l < "$work/mvn.log")" == 1 ]] || fail "repeated after a failing test"
echo "Ops OK"
