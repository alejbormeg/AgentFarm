#!/usr/bin/env bash
# ./agentfarm backup|restore against a real Compose service, in a throwaway project with its own
# volume and port, so it is safe beside a live panel. Reuses agentfarm:local or builds it if absent.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
port="${BACKUP_TEST_PORT:-18091}"
cat >"$work/ports.yaml" <<YAML
services:
  agentfarm:
    ports: !override
      - "127.0.0.1:$port:8080"
YAML
export COMPOSE_PROJECT_NAME="agentfarm-backup-test-$$"
export COMPOSE_FILE="$root/compose.yaml:$work/ports.yaml"
export DOCKER_CONFIG="${DOCKER_CONFIG:-$HOME/.docker}"
export HOME="$work/home"
mkdir -p "$HOME"
farm="$root/agentfarm"
compose() { (cd "$root" && docker compose "$@"); }
cleanup() {
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
fail() {
  echo "FAILED: $*" >&2
  compose logs --tail 40 agentfarm >&2 || true
  exit 1
}
sql() {
  python3 -I -c 'import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
for row in c.execute(sys.argv[2]): print(*row)
c.commit()' "$1" "$2"
}
file_mode() { stat -c %a "$1"; }
data_files() { compose exec -T agentfarm ls -A /data </dev/null; }
panel_up() {
  [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/login")" == 200 ]]
}

echo "== start"
compose up --wait -d

echo "== backup: explicit destination, in a directory that does not exist yet"
"$farm" backup "$work/new/a.db" >"$work/out.txt" || fail "backup failed"
[[ -s "$work/new/a.db" ]] || fail "empty backup"
[[ "$(file_mode "$work/new/a.db")" == 600 ]] || fail "backup is not 0600"
[[ "$(file_mode "$work/new")" == 700 ]] || fail "created directory is not 0700"
[[ "$(sql "$work/new/a.db" 'PRAGMA integrity_check')" == ok ]] || fail "integrity_check"
[[ "$(sql "$work/new/a.db" 'SELECT COUNT(*)>0 FROM schema_migrations')" == 1 ]] || fail "no panel schema"
sha="$(sha256sum "$work/new/a.db" | cut -d' ' -f1)"
grep -qx "SHA-256: $sha" "$work/out.txt" || fail "reported hash differs from the file"
grep -qx 'Integridad: ok' "$work/out.txt" || fail "integrity not reported"
[[ -z "$(compose exec -T agentfarm ls -A /tmp </dev/null | grep agentfarm-backup || true)" ]] ||
  fail "snapshot left in the container"
[[ -z "$(find "$work/new" -name '*.partial.*')" ]] || fail "partial file left on the host"

echo "== backup: existing file is refused and left untouched"
if "$farm" backup "$work/new/a.db" 2>/dev/null; then fail "overwrote a backup"; fi
[[ "$(sha256sum "$work/new/a.db" | cut -d' ' -f1)" == "$sha" ]] || fail "existing backup changed"

echo "== backup: default destination and relative path"
"$farm" backup >/dev/null
ls "$HOME"/agentfarm-backups/agentfarm-*.db >/dev/null || fail "no default backup"
(cd "$work" && "$farm" backup relative.db >/dev/null)
[[ -s "$work/relative.db" ]] || fail "relative destination not resolved from the caller"
mkdir "$work/dir"
"$farm" backup "$work/dir" >/dev/null
ls "$work"/dir/agentfarm-*.db >/dev/null || fail "directory destination"

echo "== backup: a host without sqlite3 or python3 relies on the container check and the hash"
mkdir "$work/bare"
for tool in bash cat cut date dirname docker grep mkdir mktemp mv rm sha256sum; do
  ln -s "$(command -v "$tool")" "$work/bare/$tool"
done
PATH="$work/bare" "$farm" backup "$work/bare.db" >"$work/out.txt" || fail "backup without SQLite tools"
grep -q '^Integridad: ok en el contenedor' "$work/out.txt" || fail "container integrity not reported"

echo "== backup: failure removes the partial file and the snapshot"
mkdir -p "$work/shim"
cat >"$work/shim/sha256sum" <<'SHIM'
#!/usr/bin/env bash
echo "0000000000000000000000000000000000000000000000000000000000000000  $1"
SHIM
chmod +x "$work/shim/sha256sum"
if PATH="$work/shim:$PATH" "$farm" backup "$work/mismatch.db" 2>/dev/null; then fail "hash mismatch accepted"; fi
[[ -z "$(find "$work" -maxdepth 1 -name 'mismatch.db*')" ]] || fail "mismatch left files on the host"
[[ -z "$(compose exec -T agentfarm ls -A /tmp </dev/null | grep agentfarm-backup || true)" ]] ||
  fail "mismatch left the snapshot in the container"

echo "== restore: refuses without confirmation, and rejects a bad snapshot with the panel running"
before="$(compose ps -q agentfarm)"
if "$farm" restore "$work/new/a.db" </dev/null 2>/dev/null; then fail "restored without --yes"; fi
head -c 8192 /dev/urandom >"$work/garbage.db"
if "$farm" restore --yes "$work/garbage.db" >/dev/null 2>&1; then fail "restored garbage"; fi
panel_up || fail "panel down after a rejected snapshot"
[[ "$(compose ps -q --status running agentfarm)" == "$before" ]] || fail "panel restarted for a bad snapshot"
[[ -z "$(data_files | grep -E 'restore' || true)" ]] || fail "rejected snapshot left files in the volume"

echo "== restore: the marked snapshot becomes the live database; the original is kept"
cp "$work/new/a.db" "$work/marked.db"
sql "$work/marked.db" 'CREATE TABLE restore_marker(x)'
"$farm" restore --yes "$work/marked.db" >"$work/out.txt" || fail "restore failed"
panel_up || fail "panel down after restore"
"$farm" backup "$work/after.db" >/dev/null
[[ "$(sql "$work/after.db" "SELECT COUNT(*) FROM sqlite_master WHERE name='restore_marker'")" == 1 ]] ||
  fail "restored data is not live"
data_files | grep -q '^agentfarm.db.pre-restore-' || fail "original database not kept"
[[ -z "$(data_files | grep -E '^restore-' || true)" ]] || fail "temporary restore files left"
compose exec -T agentfarm test -s /data/admin-password.txt </dev/null || fail "password file lost"

echo "== restore: a snapshot this version refuses to open rolls back to the previous database"
cp "$work/after.db" "$work/newer.db"
sql "$work/newer.db" "INSERT INTO schema_migrations VALUES(999999,'future','2099-01-01')"
sql "$work/newer.db" 'CREATE TABLE future_marker(x)'
if "$farm" restore --yes "$work/newer.db" >/dev/null 2>&1; then fail "panel accepted a newer schema"; fi
panel_up || fail "rollback did not bring the panel back"
"$farm" backup "$work/rolled.db" >/dev/null
[[ "$(sql "$work/rolled.db" "SELECT COUNT(*) FROM sqlite_master WHERE name='future_marker'")" == 0 ]] ||
  fail "rollback kept the refused snapshot"
[[ "$(sql "$work/rolled.db" "SELECT COUNT(*) FROM sqlite_master WHERE name='restore_marker'")" == 1 ]] ||
  fail "rollback lost the previous database"

echo "== JAR path: flags always use it; without a running service, [dest] falls back to it"
cat >"$work/shim/java" <<SHIM
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$work/java.log"
for ((i = 1; i <= \$#; i++)); do
  if [[ "\${!i}" == --output ]]; then next=\$((i + 1)); : >"\${!next}"; fi
done
exit "\${FAKE_JAVA_STATUS:-0}"
SHIM
chmod +x "$work/shim/java"
rm "$work/shim/sha256sum"
: >"$work/fake.jar"
jar_farm() { AGENTFARM_JAR="$work/fake.jar" PATH="$work/shim:$PATH" "$farm" "$@"; }
jar_farm backup --db x.db --output y.db
grep -q -- 'backup --db x.db --output y.db' "$work/java.log" || fail "flags did not reach the JAR"
jar_farm restore --snapshot s.db --db d.db
grep -q -- 'restore --snapshot s.db --db d.db' "$work/java.log" || fail "restore flags did not reach the JAR"
compose stop agentfarm
AGENTFARM_DB=/some/farm.db jar_farm backup "$work/fallback.db" 2>/dev/null
grep -q -- "backup --db /some/farm.db --output $work/fallback.db" "$work/java.log" || fail "no JAR fallback"
if FAKE_JAVA_STATUS=4 jar_farm backup "$work/failed.db" 2>/dev/null; then fail "JAR failure ignored"; fi
[[ ! -e "$work/failed.db" ]] || fail "failed JAR backup left an empty file"

echo "== restore: also works when the service is stopped, and starts it"
"$farm" restore --yes "$work/new/a.db" >/dev/null || fail "restore on a stopped service"
panel_up || fail "panel not started by restore"

echo "compose backup and restore passed"
