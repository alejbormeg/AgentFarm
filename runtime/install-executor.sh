#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
config="${1:?Usage: ./install-executor.sh /absolute/path/executor.env}"
[[ "$config" = /* && -f "$config" ]] || { echo "An existing absolute environment file is required" >&2; exit 2; }
[[ "$config" != *" "* && "$config" != *$'\t'* ]] || { echo "Environment file path must not contain whitespace" >&2; exit 2; }
[[ "$root$config" != *$'\n'* && "$root$config" != *%* && "$root$config" != *'"'* ]] || exit 2
command -v java >/dev/null
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/agentfarm-executor.service" <<EOF
[Unit]
Description=AgentFarm executor
After=network-online.target
[Service]
WorkingDirectory=$root
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
EnvironmentFile=$config
ExecStart="$root/executor"
Restart=on-failure
RestartSec=10
KillMode=control-group
TimeoutStopSec=15
UMask=0077
[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now agentfarm-executor.service
