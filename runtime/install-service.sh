#!/usr/bin/env bash
# Compatibility entry point. Enrollment and configuration are now explicit.
set -euo pipefail
cd "$(dirname "$0")/.."
config="${1:?Usage: bash runtime/install-service.sh /absolute/path/executor.env}"
bash runtime/package.sh
mkdir -p "$HOME/.local/lib/agentfarm"
dist=$(mktemp -d "$HOME/.local/lib/agentfarm/executor.XXXXXX")
tar -C "$dist" -xzf target/agentfarm-executor-linux.tar.gz
exec "$dist/agentfarm-executor/install-executor.sh" "$config"
