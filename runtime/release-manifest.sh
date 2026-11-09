#!/usr/bin/env bash
# What a release is, by its exact commit: the gates it passed here, the hashes of what ships, the image,
# its SBOM and the vulnerabilities kept for follow-up. Written by validar-local.sh after every gate passed.
# Usage: release-manifest.sh <commit> <output-dir> <image>. Run from the validated tree.
set -euo pipefail
commit=$1; out=$2; image=$3
mkdir -p "$out"
sha() { sha256sum "$1" | cut -d' ' -f1; }
python3 -I - "$commit" "$out" "$image" \
  "$(sha target/agentfarm.jar)" \
  "$( [[ -f target/agentfarm-executor-linux.tar.gz ]] && sha target/agentfarm-executor-linux.tar.gz || echo missing)" \
  "$(docker image inspect --format '{{.Id}}' "$image")" \
  "$(sha "$out/sbom.cdx.json")" \
  "$( [[ -f .trivyignore ]] && cat .trivyignore || true)" <<'PY'
import json, sys, datetime, collections
commit, out, image, jar, executor, image_id, sbom, ignored = sys.argv[1:9]
vulns = json.load(open(f"{out}/image-vulnerabilities.json"))
count = collections.Counter()
for r in vulns.get("Results") or []:
    for v in r.get("Vulnerabilities") or []:
        count[v.get("Severity", "UNKNOWN") + (" fixable" if v.get("FixedVersion") else " unfixed")] += 1
manifest = {
    "commit": commit,
    "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    # Where this was verified: here, on this machine, not in Actions and not on a deployment.
    "verification": "local: validar-local.sh (no GitHub Actions, no real deployment)",
    "gates": [
        "maven verify (unit, architecture, API contract, format)",
        "runtime: verify.sh, package.sh, credentials, ops, merge gate, pre-push, pilot",
        "security: Trivy on the jar, gitleaks on the history",
        "e2e with doubles: panel + packaged executor + browser smoke",
        "container: compose up, HTTP checks, dedicated user, backup",
        "image: Trivy on the final image (system packages and libraries), SBOM",
    ],
    "artifacts": {
        "agentfarm.jar": {"sha256": jar},
        "agentfarm-executor-linux.tar.gz": {"sha256": executor},
        "image": {"tag": image, "id": image_id},
        "sbom.cdx.json": {"sha256": sbom},
    },
    "vulnerabilities": {
        "gate": "no HIGH or CRITICAL with a fix in the jar or the image",
        "kept_for_follow_up": dict(sorted(count.items())),
        "report": "image-vulnerabilities.json",
        "exceptions": [l for l in ignored.splitlines() if l.strip()],
    },
}
json.dump(manifest, open(f"{out}/manifest.json", "w"), indent=2)
print(f"Manifiesto de {commit[:7]} en {out}/manifest.json")
PY
