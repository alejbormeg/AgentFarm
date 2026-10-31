#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
CP="target/classes:$(find "$HOME/.m2/repository/com/fasterxml/jackson/core" "$HOME/.m2/repository/org/yaml/snakeyaml" -name '*.jar' | paste -sd:)"
mkdir -p target/runtime
javac -cp "$CP" -d target/runtime runtime/CodexRunner.java runtime/FarmDaemon.java runtime/RuntimeChecks.java
java -cp "target/runtime:$CP" RuntimeChecks
java -cp "target/runtime:$CP" CodexRunner guard-test
