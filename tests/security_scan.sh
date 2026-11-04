#!/usr/bin/env bash
# Known vulnerabilities in what ships, and secrets in the history. Both run in pinned containers, need no
# keys and send no code anywhere: Trivy only downloads its database. Needs target/agentfarm.jar.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
trivy=aquasec/trivy@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c # 0.76.1
gitleaks=zricethezav/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f # v8.30.1
cache="${TRIVY_CACHE:-$HOME/.cache/agentfarm-trivy}"
mkdir -p "$cache"
[[ -f "$root/target/agentfarm.jar" ]] || { echo "Falta target/agentfarm.jar: compila antes." >&2; exit 2; }

docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e TRIVY_CACHE_DIR=/cache \
  -v "$cache:/cache" -v "$root/target:/scan:ro" "$trivy" rootfs --quiet --scanners vuln \
  --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 /scan/agentfarm.jar
echo "Sin vulnerabilidades altas o críticas con arreglo en el jar"

# A worktree keeps its history in the main repository, so that one is what gets mounted.
common="$(cd "$(git rev-parse --git-common-dir)/.." && pwd)"
docker run --rm --user "$(id -u):$(id -g)" -v "$common:/repo:ro" -v "$root/.gitleaksignore:/ignore:ro" "$gitleaks" \
  git /repo --redact --no-banner --log-level warn --gitleaks-ignore-path /ignore --log-opts "$(git rev-parse HEAD)"
echo "Sin secretos en el historial"
