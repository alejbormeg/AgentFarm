#!/usr/bin/env bash
# The image that ships, not only the jar: its system packages and libraries. Gate: no HIGH or CRITICAL
# with a fix. Kept for follow-up: every finding, any severity, fixed or not, and the SBOM.
# Usage: image_scan.sh <image> <output-dir>. Pinned Trivy; the image goes in as a tar, no Docker socket.
set -euo pipefail
image=$1; out=$2
trivy=aquasec/trivy@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c # 0.76.1
cache="${TRIVY_CACHE:-$HOME/.cache/agentfarm-trivy}"
mkdir -p "$cache" "$out"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
docker save "$image" -o "$tmp/image.tar"
run() {
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e TRIVY_CACHE_DIR=/cache \
    -v "$cache:/cache" -v "$tmp:/scan:ro" -v "$out:/out" "$trivy" image --quiet --input /scan/image.tar "$@"
}
run --scanners vuln --format json --output /out/image-vulnerabilities.json
run --format cyclonedx --output /out/sbom.cdx.json
run --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1
python3 -I - "$out/image-vulnerabilities.json" <<'PY'
import json, sys, collections
report = json.load(open(sys.argv[1]))
count = collections.Counter()
for result in report.get("Results") or []:
    for v in result.get("Vulnerabilities") or []:
        count[(v.get("Severity", "UNKNOWN"), "con arreglo" if v.get("FixedVersion") else "sin arreglo")] += 1
summary = ", ".join(f"{n} {s.lower()} {f}" for (s, f), n in sorted(count.items())) or "ninguna"
print("Imagen sin vulnerabilidades altas o críticas con arreglo. En seguimiento: " + summary)
PY
