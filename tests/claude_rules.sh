#!/usr/bin/env bash
# Runs tests/claude_rules.py with the executor's real command. See that file before running: it spends account quota.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -d target/classes ]] || ./mvnw -B -ntp -q -DskipTests compile
CP="target/classes:$(find "$HOME/.m2/repository/com/fasterxml/jackson/core" -name '*.jar' | paste -sd:)"
claude --version
exec python3 -I tests/claude_rules.py java "$CP" tests/ClaudeArgv.java "${CLAUDE_RULES_MODEL:-claude-haiku-5-5}"
