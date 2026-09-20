#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${ROOT}/.env.dev"
REPORT_TASK="${ROOT}/report-task.txt"
COVERAGE_XML="${ROOT}/target/site/jacoco/jacoco.xml"

[[ -f "${ENV_FILE}" ]] || {
  echo "ERROR: No existe .env.dev" >&2
  exit 1
}

[[ -s "${COVERAGE_XML}" ]] || {
  echo "ERROR: target/site/jacoco/jacoco.xml no existe. Ejecute primero scripts/coverage.sh" >&2
  exit 1
}

[[ -d "${ROOT}/target/classes" ]] || {
  echo "ERROR: target/classes no existe. Ejecute primero scripts/coverage.sh" >&2
  exit 1
}

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

: "${SONAR_HOST_URL:?ERROR: SONAR_HOST_URL no configurado}"
: "${SONAR_TOKEN:?ERROR: SONAR_TOKEN no configurado}"
SONAR_API_URL="${SONAR_API_URL:-${SONAR_HOST_URL}}"

cd "${ROOT}"
rm -rf .scannerwork
rm -f "${REPORT_TASK}"
mkdir -p .sonar/cache

docker run --rm \
  --network sbm-network \
  -e SONAR_HOST_URL="${SONAR_HOST_URL}" \
  -e SONAR_TOKEN="${SONAR_TOKEN}" \
  -v "${ROOT}/.sonar/cache:/opt/sonar-scanner/.sonar/cache" \
  -v "${ROOT}:/usr/src/app" \
  -w /usr/src/app \
  sonarsource/sonar-scanner-cli:latest \
  -Dsonar.scanner.metadataFilePath=/usr/src/app/report-task.txt

[[ -f "${REPORT_TASK}" ]] || {
  echo "ERROR: SonarScanner no generó report-task.txt" >&2
  exit 1
}

ce_task_id="$(awk -F= '$1=="ceTaskId" {print $2}' "${REPORT_TASK}")"
[[ -n "${ce_task_id}" ]] || {
  echo "ERROR: report-task.txt no contiene ceTaskId" >&2
  exit 1
}

analysis_id=""
for _ in $(seq 1 60); do
  response="$(curl --fail --silent --show-error \
    -u "${SONAR_TOKEN}:" \
    "${SONAR_API_URL%/}/api/ce/task?id=${ce_task_id}")"

  status="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["task"]["status"])' <<< "${response}")"

  case "${status}" in
    SUCCESS)
      analysis_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["task"].get("analysisId", ""))' <<< "${response}")"
      break
      ;;
    FAILED|CANCELED)
      echo "ERROR: Sonar compute task terminó en ${status}" >&2
      exit 1
      ;;
  esac

  sleep 2
done

[[ -n "${analysis_id}" ]] || {
  echo "ERROR: No se obtuvo analysisId de SonarQube" >&2
  exit 1
}

qg_response="$(curl --fail --silent --show-error \
  -u "${SONAR_TOKEN}:" \
  "${SONAR_API_URL%/}/api/qualitygates/project_status?analysisId=${analysis_id}")"

quality_gate="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["projectStatus"]["status"])' <<< "${qg_response}")"

case "${quality_gate}" in
  OK)
    echo "SonarScanner: SUCCESS"
    echo "Quality Gate: PASSED"
    ;;
  *)
    echo "SonarScanner: SUCCESS"
    echo "Quality Gate: FAILED"
    exit 1
    ;;
esac
