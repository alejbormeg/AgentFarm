#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${MVN:-./mvnw}" bash runtime/mvn-retry.sh -B -ntp -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/dependency -DincludeGroupIds=com.fasterxml.jackson.core,org.yaml
stage=$(mktemp -d "$PWD/target/executor-dist.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
out="$stage/agentfarm-executor"
mkdir -p "$out/lib" "$stage/classes"
cp target/dependency/*.jar "$out/lib/"
javac --release 21 -cp "target/classes:$out/lib/*" -d "$stage/classes" runtime/CodexRunner.java runtime/FarmDaemon.java
# Everything these two compile to, nested classes included. jdeps below only checks the core list.
jar --create --file "$out/executor.jar" -C "$stage/classes" . -C target/classes io/agentfarm/core/Json.class -C target/classes io/agentfarm/core/ProjectProfile.class
for name in ExecutorWork\$Verdict ExecutorWork\$Candidate SkillCatalog SkillCatalog\$Skill SkillCatalog\$Selection TestPolicy SecretScan SecretScan\$Coverage Redaction Redaction\$Rule ChangePolicy ChangePolicy\$Change ChangePolicy\$GitHubFile QualityGate Commandments Commandments\$Verdict ProjectMemory AgentAdapter AgentAdapters CodexAdapter UnavailableAdapter TestSandbox TestSandbox\$Policy TestSandbox\$Output TestSandbox\$Runner Prompts Prompts\$Rendered ClaudeCodeAdapter ClaudeCodeAdapter\$Result ClaudeCodeAdapter\$Failed McpCatalog McpCatalog\$Server ProposalShape BuildInfo ProfileCheck Backoff AgentSandbox SandboxProbe ExecutorWork; do
  jar --update --file "$out/executor.jar" -C target/classes "io/agentfarm/core/$name.class"
done
jar --update --file "$out/executor.jar" -C target/classes io/agentfarm/adapters/GitChanges.class
jar --update --file "$out/executor.jar" -C target/classes io/agentfarm/adapters/ProposalGraph.class
jar --update --file "$out/executor.jar" -C target/classes io/agentfarm/adapters/GitHubText.class
jar --update --file "$out/executor.jar" -C target/classes io/agentfarm/adapters/ProfilePrompt.class
jar --update --file "$out/executor.jar" -C target/classes 'io/agentfarm/adapters/ProfilePrompt$Section.class'
# The image build has no .git: it passes the commit it was built from.
if [[ -n "${AGENTFARM_COMMIT:-}" ]]; then printf '%s\n' "$AGENTFARM_COMMIT" > "$stage/build-commit.txt"
else git rev-parse HEAD 2>/dev/null > "$stage/build-commit.txt" || echo unknown > "$stage/build-commit.txt"; fi
jar --update --file "$out/executor.jar" -C "$stage" build-commit.txt
jar --update --file "$out/executor.jar" -C target/classes prompts
jar --update --file "$out/executor.jar" -C target/classes skills
# The class list above is by hand: a missing one would only fail on a real executor.
# -filter:none: by default jdeps hides what a class needs from its own package, the core's gaps included.
deps=$(jdeps --multi-release 21 -filter:none -verbose:class -cp "$out/lib/*" "$out/executor.jar")
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
