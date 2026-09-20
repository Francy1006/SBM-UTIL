#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RESULT_FILE="${ROOT}/context/qa-results.md"
COVERAGE_LOG="$(mktemp)"
SONAR_LOG="$(mktemp)"
trap 'rm -f "${COVERAGE_LOG}" "${SONAR_LOG}"' EXIT

mkdir -p "${ROOT}/context"

coverage_exit=0
echo "1/2 Ejecutando pruebas y coverage..."
set +e
"${SCRIPT_DIR}/coverage.sh" 2>&1 | tee "${COVERAGE_LOG}"
coverage_exit="${PIPESTATUS[0]}"
set -e

tests_total=0
failed_tests=0
skipped_tests=0
passed_tests=0
if [[ -d "${ROOT}/target/surefire-reports" ]]; then
  read -r tests_total failed_tests skipped_tests passed_tests < <(
    python3 - "${ROOT}/target/surefire-reports" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

reports = sorted(Path(sys.argv[1]).glob("TEST-*.xml"))
tests = failures = errors = skipped = 0
for report in reports:
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError:
        continue
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))
failed = failures + errors
passed = max(tests - failed - skipped, 0)
print(tests, failed, skipped, passed)
PY
  )
fi

coverage_result="N/A"
if [[ -s "${ROOT}/target/site/jacoco/jacoco.xml" ]]; then
  coverage_result="$(python3 - "${ROOT}/target/site/jacoco/jacoco.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for counter in root.findall("counter"):
    if counter.attrib.get("type") == "LINE":
        missed = int(counter.attrib.get("missed", 0))
        covered = int(counter.attrib.get("covered", 0))
        total = missed + covered
        print(f"{(covered / total * 100):.2f}%" if total else "N/A")
        break
else:
    print("N/A")
PY
)"
fi

sonar_exit=0
scanner_result="not-run"
quality_gate="N/A"
if [[ "${coverage_exit}" -eq 0 && "${tests_total}" -gt 0 && "${failed_tests}" -eq 0 ]]; then
  echo "2/2 Ejecutando SonarScanner..."
  set +e
  "${SCRIPT_DIR}/sonar-scan.sh" 2>&1 | tee "${SONAR_LOG}"
  sonar_exit="${PIPESTATUS[0]}"
  set -e

  if grep -q '^SonarScanner: SUCCESS$' "${SONAR_LOG}"; then
    scanner_result="success"
  else
    scanner_result="failed"
  fi
  if grep -q '^Quality Gate: PASSED$' "${SONAR_LOG}"; then
    quality_gate="PASSED"
  elif grep -q '^Quality Gate: FAILED$' "${SONAR_LOG}"; then
    quality_gate="FAILED"
  else
    quality_gate="UNAVAILABLE"
  fi
else
  echo "2/2 SonarScanner no ejecutado porque tests/coverage fallaron."
fi

overall_status="passed"
if [[ "${coverage_exit}" -ne 0 \
   || "${tests_total}" -eq 0 \
   || "${failed_tests}" -ne 0 \
   || "${sonar_exit}" -ne 0 \
   || "${quality_gate}" != "PASSED" ]]; then
  overall_status="failed"
fi

cat > "${RESULT_FILE}" <<EOF2
# QA Results

Generated timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
Project: SBM-UTIL
Overall status: ${overall_status}

## Tests and coverage

Test exit code: ${coverage_exit}
Collected tests: ${tests_total}
Passed tests: ${passed_tests}
Failed tests: ${failed_tests}
Skipped tests: ${skipped_tests}
Coverage result: ${coverage_result}
Coverage artifact: target/site/jacoco/jacoco.xml

## SonarQube

SonarScanner exit code: ${sonar_exit}
Scanner execution result: ${scanner_result}
Server-side Quality Gate result: ${quality_gate}

## Evidence

QA execution command: ./scripts/qa-check.sh
Runtime: Docker
Java runtime: 21
Build tool: Maven (containerized)
EOF2

echo "Evidencia QA generada en: context/qa-results.md"

[[ "${overall_status}" == "passed" ]] || exit 1
echo "QA SBM-UTIL completado correctamente."
