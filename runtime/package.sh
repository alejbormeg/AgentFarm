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
for name in SkillCatalog SkillCatalog\$Skill SkillCatalog\$Selection TestPolicy SecretScan QualityGate Commandments Commandments\$Verdict ProjectMemory AgentAdapter AgentAdapters CodexAdapter UnavailableAdapter TestSandbox TestSandbox\$Policy TestSandbox\$Output TestSandbox\$Runner Prompts Prompts\$Rendered ClaudeCodeAdapter ClaudeCodeAdapter\$Result McpCatalog McpCatalog\$Server ProposalShape BuildInfo; do
  jar --update --file "$out/executor.jar" -C target/classes "io/agentfarm/core/$name.class"
done
git rev-parse HEAD 2>/dev/null > "$stage/build-commit.txt" || echo unknown > "$stage/build-commit.txt"
jar --update --file "$out/executor.jar" -C "$stage" build-commit.txt
jar --update --file "$out/executor.jar" -C target/classes prompts
jar --update --file "$out/executor.jar" -C target/classes skills
# The class list above is by hand: a missing one would only fail on a real executor.
deps=$(jdeps --multi-release 21 -verbose:class -cp "$out/lib/*" "$out/executor.jar")
missing=$(grep -E 'io\.agentfarm\.[^ ]+ +not found' <<<"$deps" || true)
if [[ -n "$missing" ]]; then
  echo "Executor jar is missing classes:" >&2
  echo "$missing" >&2
  exit 1
fi
cp runtime/executor runtime/install-executor.sh "$out/"
cp LICENSE NOTICE "$out/"
cp docs/INSTALLATION.md "$out/README.md"
chmod +x "$out/executor" "$out/install-executor.sh"
tar -C "$stage" -czf target/agentfarm-executor-linux.tar.gz agentfarm-executor
