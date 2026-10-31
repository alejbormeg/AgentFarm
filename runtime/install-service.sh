#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT_DIR="$(pwd)"
CP="$(find "$HOME/.m2/repository/com/fasterxml/jackson/core" -name '*.jar' | paste -sd:)"
mkdir -p target/runtime "$HOME/.config/systemd/user"
javac -cp "$CP" -d target/runtime runtime/CodexRunner.java runtime/FarmDaemon.java
cat > "$HOME/.config/systemd/user/agentfarm-executor.service" <<EOF
[Unit]
Description=AgentFarm WSL executor and telemetry
After=network-online.target
[Service]
WorkingDirectory=$ROOT_DIR
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/java -cp $ROOT_DIR/target/runtime:$CP FarmDaemon
Restart=on-failure
RestartSec=10
KillMode=control-group
TimeoutStopSec=15
UMask=0077
[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable agentfarm-executor
systemctl --user restart agentfarm-executor
