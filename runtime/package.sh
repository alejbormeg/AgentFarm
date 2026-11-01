#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B -ntp -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/dependency -DincludeGroupIds=com.fasterxml.jackson.core,org.yaml
stage=$(mktemp -d "$PWD/target/executor-dist.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
out="$stage/agentfarm-executor"
mkdir -p "$out/lib" target/runtime
cp target/dependency/*.jar "$out/lib/"
javac --release 21 -cp "target/classes:$out/lib/*" -d target/runtime runtime/CodexRunner.java runtime/FarmDaemon.java
jar --create --file "$out/executor.jar" -C target/runtime CodexRunner.class -C target/runtime FarmDaemon.class -C target/classes io/agentfarm/core/Json.class -C target/classes io/agentfarm/core/ProjectProfile.class
for name in ProjectMemory AgentAdapter AgentAdapters CodexAdapter UnavailableAdapter TestSandbox TestSandbox\$Policy TestSandbox\$Output TestSandbox\$Runner Prompts Prompts\$Rendered; do
  jar --update --file "$out/executor.jar" -C target/classes "io/agentfarm/core/$name.class"
done
jar --update --file "$out/executor.jar" -C target/classes prompts
cp runtime/executor runtime/install-executor.sh "$out/"
cp LICENSE NOTICE "$out/"
cp docs/INSTALLATION.md "$out/README.md"
chmod +x "$out/executor" "$out/install-executor.sh"
tar -C "$stage" -czf target/agentfarm-executor-linux.tar.gz agentfarm-executor
