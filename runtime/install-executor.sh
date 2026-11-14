#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
config="${1:?Usage: ./install-executor.sh /absolute/path/executor.env [slot] [name]}"
slot="${2:-}"
name="${3:-}"
[[ -z "$name" || "$name" =~ ^[A-Za-z0-9_-]{1,80}$ ]] || { echo "Name must be letters, digits, - or _" >&2; exit 2; }
[[ -z "$slot" || "$slot" =~ ^[1-9][0-9]?$ ]] || { echo "Slot must be 1-99" >&2; exit 2; }
# A name lets several executors live on one machine, each with its own unit.
unit="agentfarm-executor${name:+-$name}${slot:+-s$slot}"
[[ "$config" = /* && -f "$config" ]] || { echo "An existing absolute environment file is required" >&2; exit 2; }
[[ "$config" != *" "* && "$config" != *$'\t'* ]] || { echo "Environment file path must not contain whitespace" >&2; exit 2; }
[[ "$root$config" != *$'\n'* && "$root$config" != *%* && "$root$config" != *'"'* ]] || exit 2
command -v java >/dev/null
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/$unit.service" <<EOF
[Unit]
Description=AgentFarm executor
After=network-online.target
[Service]
WorkingDirectory=$root
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
EnvironmentFile=$config
${slot:+Environment=AGENTFARM_SLOT=$slot}
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
systemctl --user enable "$unit.service"
systemctl --user restart "$unit.service"

# Keep this copy, the previous one and any other unit still points at.
parent="$(dirname "$root")"
lib="$(dirname "$parent")"
if [[ "$(basename "$parent")" == executor.* ]]; then
  ls -1dt "$lib"/executor.* | tail -n +3 | while read -r old; do
    [[ "$old" != "$parent" ]] || continue
    grep -qsF "$old/" "$HOME"/.config/systemd/user/agentfarm-executor*.service || rm -rf -- "$old"
  done
fi
