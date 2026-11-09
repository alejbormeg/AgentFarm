#!/usr/bin/env bash
# The vendored bundles are what INVENTORY.md says: same files, same hash.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)/src/main/resources/static/vendor"
actual="$(find mermaid -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)"
grep -q "\`$actual\`" INVENTORY.md || { echo "vendor/mermaid no coincide con INVENTORY.md ($actual)" >&2; exit 1; }
echo "Código de terceros conforme a su inventario"
