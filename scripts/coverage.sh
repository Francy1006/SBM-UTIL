#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${ROOT}/.env.dev"
COVERAGE_XML="${ROOT}/target/site/jacoco/jacoco.xml"
SUREFIRE_DIR="${ROOT}/target/surefire-reports"
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9-eclipse-temurin-21}"
MAVEN_CACHE_VOLUME="${MAVEN_CACHE_VOLUME:-sbm-util-maven-cache}"

[[ -f "${ENV_FILE}" ]] || {
  echo "ERROR: No existe .env.dev" >&2
  exit 1
}

cd "${ROOT}"
rm -rf target

echo "Ejecutando tests Maven + JaCoCo en Docker..."
docker run --rm \
  --env-file "${ENV_FILE}" \
  -v "${ROOT}:/app" \
  -v "${MAVEN_CACHE_VOLUME}:/root/.m2" \
  -w /app \
  "${MAVEN_IMAGE}" \
  mvn -B clean verify

[[ -s "${COVERAGE_XML}" ]] || {
  echo "ERROR: No se generó target/site/jacoco/jacoco.xml" >&2
  exit 1
}

[[ -d "${SUREFIRE_DIR}" ]] || {
  echo "ERROR: Maven no generó target/surefire-reports" >&2
  exit 1
}

python3 - "${SUREFIRE_DIR}" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

report_dir = Path(sys.argv[1])
reports = sorted(report_dir.glob("TEST-*.xml"))
if not reports:
    raise SystemExit("ERROR: SBM-UTIL no generó reportes Surefire")

tests = failures = errors = skipped = 0
for report in reports:
    root = ET.parse(report).getroot()
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))

if tests <= 0:
    raise SystemExit("ERROR: SBM-UTIL no tiene tests ejecutables")
if failures or errors:
    raise SystemExit(
        f"ERROR: Surefire reporta failures={failures}, errors={errors}"
    )

passed = tests - failures - errors - skipped
print(
    f"Tests Surefire: total={tests}, passed={passed}, "
    f"failed={failures + errors}, skipped={skipped}"
)
PY

echo "Coverage artifact: target/site/jacoco/jacoco.xml"
