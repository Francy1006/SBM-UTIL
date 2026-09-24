#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SUITE_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
source "${SUITE_ROOT}/context/scripts/sonar-scanner-common.sh"

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

SONAR_ARCH="$(sbm_sonar_detect_arch)"
SONAR_SCANNER_PLATFORM="$(sbm_sonar_platform)"
SONAR_CACHE_DIR="$(sbm_sonar_cache_dir "${ROOT}" "${SONAR_ARCH}")"
SONAR_CONTAINER_NAME="sbm-sonar-${SONAR_ARCH}-$$"

cd "${ROOT}"
rm -rf .scannerwork
rm -f "${REPORT_TASK}"
mkdir -p "${SONAR_CACHE_DIR}"

docker_args=(
  docker
  run
  --rm
  --name
  "${SONAR_CONTAINER_NAME}"
  --platform
  "${SONAR_SCANNER_PLATFORM}"
  --network
  "sbm-network"
  -e
  "SONAR_HOST_URL=${SONAR_HOST_URL}"
  -e
  "SONAR_TOKEN=${SONAR_TOKEN}"
  -v
  "${SONAR_CACHE_DIR}:/opt/sonar-scanner/.sonar/cache"
  -v
  "${ROOT}:/usr/src/app"
  -w
  "/usr/src/app"
  "$(sbm_sonar_image)"
  "-Dsonar.scanner.metadataFilePath=/usr/src/app/report-task.txt"
)

sbm_sonar_ensure_image
sbm_sonar_run "${docker_args[@]}"

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
