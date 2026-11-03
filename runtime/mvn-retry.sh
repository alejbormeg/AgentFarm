#!/usr/bin/env bash
# Runs Maven and repeats it only when a download was cut; a failing test fails at once.
set -uo pipefail
for attempt in 1 2 3; do
  log="$(mktemp)"
  "${MVN:-mvn}" "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  [[ "$status" == 0 ]] && exit 0
  grep -qE 'Could not transfer artifact|Premature end of|Connection reset|Read timed out' "$log" || exit "$status"
  echo "Descarga interrumpida; reintento $attempt de 3" >&2
  sleep $((attempt * ${MVN_RETRY_DELAY:-5}))
done
exit "$status"
