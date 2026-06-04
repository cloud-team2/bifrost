#!/usr/bin/env bash
set -euo pipefail

# ──────────────────────────────────────────────────────────────────────────────
# EDA/CDC 파이프라인 real E2E smoke (#76 갱신)
#
# operations-backend real provisioner가 EDA(FAN_OUT)/CDC(DIRECT) 파이프라인을
# 실제로 생성하고 KafkaConnector가 RUNNING으로 수렴하는지, 토픽이 생성되는지로
# GREEN/RED를 판정한다. 판정 기준은 docs/guides/pipeline-e2e-smoke.md 참조.
#
# 사용법:
#   BASE_URL=http://localhost:8080 \
#   SRC_SECRET_REF=<secretRef> SINK_SECRET_REF=<secretRef> \
#   ./scripts/pipeline-e2e-smoke.sh [eda|cdc|all]
#
# 사전 조건:
#   1. operations-backend port-forward:
#      kubectl -n bifrost-system port-forward deploy/operations-backend 8080:8080
#      (로컬 개발 시: ./gradlew :services:operations-backend:bootRun 로 기동)
#   2. PROVISIONING_MODE=real 로 기동
#   3. SRC_SECRET_REF: DB 등록 API 또는 InMemorySecretStore에 등록된 secretRef
#   4. KafkaConnect platform-connect Ready
#
# 의존: kubectl, curl, jq
# 종료 코드: 0=GREEN, 1=RED, 2=전제/도구 오류
# ──────────────────────────────────────────────────────────────────────────────

SCENARIO="${1:-all}"

BASE_URL="${BASE_URL:-http://localhost:8080}"
KAFKA_NS="${KAFKA_NS:-platform-kafka}"
CONNECT_CLUSTER="${CONNECT_CLUSTER:-platform-connect}"
PROJECT_KEY="${PROJECT_KEY:-smoke}"
TIMEOUT_SEC="${TIMEOUT_SEC:-90}"
POLL_SEC="${POLL_SEC:-3}"

SRC_ENGINE="${SRC_ENGINE:-POSTGRESQL}"
SRC_HOST="${SRC_HOST:-user-postgres-service.userdb.svc.cluster.local}"
SRC_PORT="${SRC_PORT:-5432}"
SRC_DB="${SRC_DB:-testdb}"
SRC_SCHEMA="${SRC_SCHEMA:-public}"
SRC_TABLE="${SRC_TABLE:-orders}"
SRC_SECRET_REF="${SRC_SECRET_REF:-secret://smoke-src}"

SINK_ENGINE="${SINK_ENGINE:-MARIADB}"
SINK_HOST="${SINK_HOST:-user-mariadb-service.userdb.svc.cluster.local}"
SINK_PORT="${SINK_PORT:-3306}"
SINK_DB="${SINK_DB:-testdb}"
SINK_SECRET_REF="${SINK_SECRET_REF:-secret://smoke-sink}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[GREEN]${NC} $*"; }
fail() { echo -e "${RED}[RED]${NC} $*"; }
info() { echo -e "${YELLOW}[..]${NC} $*"; }

require() { command -v "$1" >/dev/null 2>&1 || { fail "필수 도구 없음: $1"; exit 2; }; }
require kubectl; require curl; require jq

# ── 사전 점검 ──────────────────────────────────────────────────────────────────
preflight() {
  # 1) KafkaConnect Ready
  info "KafkaConnect '${CONNECT_CLUSTER}' Ready 확인"
  local ready
  ready=$(kubectl get kafkaconnect "${CONNECT_CLUSTER}" -n "${KAFKA_NS}" \
            -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
  if [[ "${ready}" != "True" ]]; then
    fail "KafkaConnect가 Ready가 아님(status=${ready:-none}). 빌드/배포부터 확인."
    exit 1
  fi
  pass "KafkaConnect Ready"

  # 2) operations-backend health
  info "operations-backend health 확인 (${BASE_URL}/actuator/health)"
  local health
  health=$(curl -fsS "${BASE_URL}/actuator/health" 2>/dev/null | jq -r '.status' 2>/dev/null || echo "UNKNOWN")
  if [[ "${health}" != "UP" ]]; then
    fail "operations-backend가 UP이 아님(status=${health}). 기동 및 포트포워딩 확인."
    exit 1
  fi
  pass "operations-backend UP"
}

# ── connector RUNNING 수렴 polling ────────────────────────────────────────────
wait_running() {
  local name="$1"
  local deadline=$(( $(date +%s) + TIMEOUT_SEC ))
  while (( $(date +%s) < deadline )); do
    local resp
    # canonical 경로 우선, fallback은 legacy 경로
    resp=$(curl -fsS \
      "${BASE_URL}/internal/ops/projects/${PROJECT_KEY}/kafka/connectors/${name}/status" \
      2>/dev/null || \
      curl -fsS \
      "${BASE_URL}/internal/pipelines/status?projectId=${PROJECT_KEY}&connectorName=${name}" \
      2>/dev/null || echo '{}')
    local state
    state=$(echo "${resp}" | jq -r '.connectorState // "UNKNOWN"' 2>/dev/null || echo "UNKNOWN")
    case "${state}" in
      RUNNING) pass "connector ${name} → RUNNING"; return 0 ;;
      FAILED)
        local last_error
        last_error=$(echo "${resp}" | jq -r '.tasks[]?.trace // empty' 2>/dev/null | head -1 || true)
        fail "connector ${name} → FAILED${last_error:+: ${last_error:0:200}}"
        return 1 ;;
      *) info "connector ${name} state=${state} (대기)"; ;;
    esac
    sleep "${POLL_SEC}"
  done
  fail "connector ${name} 가 ${TIMEOUT_SEC}s 내 RUNNING 미도달 (creating 고착 또는 배포 지연)"
  return 1
}

# ── topic 존재 확인 ─────────────────────────────────────────────────────────────
expect_topic() {
  local topic="$1"
  if kubectl get kafkatopic -n "${KAFKA_NS}" 2>/dev/null \
       | awk '{print $1}' | grep -qx "${topic}"; then
    pass "topic ${topic} 존재(KafkaTopic CR)"
    return 0
  fi
  if kubectl -n "${KAFKA_NS}" exec "${CONNECT_CLUSTER}-connect-0" -- \
       bin/kafka-topics.sh --bootstrap-server "platform-kafka-kafka-bootstrap:9092" \
       --list 2>/dev/null | grep -qx "${topic}"; then
    pass "topic ${topic} 존재(broker)"
    return 0
  fi
  fail "topic ${topic} 미생성"
  return 1
}

post_pipeline() {
  local body="$1"
  local resp http_code
  resp=$(curl -fsS -w '\n%{http_code}' -X POST "${BASE_URL}/internal/pipelines" \
    -H 'Content-Type: application/json' -d "${body}" 2>/dev/null || echo -e '{}\n000')
  http_code=$(echo "${resp}" | tail -1)
  local json
  json=$(echo "${resp}" | sed '$d')

  if [[ "${http_code}" == "202" ]]; then
    echo "${json}"
    return 0
  fi
  # 실패 시 stage/errorCode 출력
  local stage errorCode
  stage=$(echo "${json}" | jq -r '.stage // "UNKNOWN"' 2>/dev/null || echo "UNKNOWN")
  errorCode=$(echo "${json}" | jq -r '.errorCode // empty' 2>/dev/null || true)
  fail "pipeline 생성 실패: HTTP=${http_code}, stage=${stage}${errorCode:+, errorCode=${errorCode}}"
  return 1
}

# ── EDA(FAN_OUT) ──────────────────────────────────────────────────────────────
run_eda() {
  echo "──────── EDA(FAN_OUT) ────────"
  local pid; pid=$(uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || python3 -c "import uuid; print(uuid.uuid4())")
  local body
  body=$(jq -nc \
    --arg pid "$pid" --arg pk "$PROJECT_KEY" \
    --arg eng "$SRC_ENGINE" --arg h "$SRC_HOST" --argjson p "$SRC_PORT" \
    --arg db "$SRC_DB" --arg sc "$SRC_SCHEMA" --arg tb "$SRC_TABLE" --arg sr "$SRC_SECRET_REF" \
    '{pipelineId:$pid, projectKey:$pk, pattern:"FAN_OUT",
      source:{engine:$eng,host:$h,port:$p,dbName:$db,schema:$sc,table:$tb,secretRef:$sr}}')
  info "POST /internal/pipelines (EDA) pipelineId=${pid}"
  post_pipeline "${body}" >/dev/null || return 1
  wait_running "${pid}-source" || return 1
  expect_topic "cdc.table.${PROJECT_KEY}.${SRC_DB}.${SRC_SCHEMA}.${SRC_TABLE}" || return 1
  pass "EDA E2E GREEN"
}

# ── CDC(DIRECT) ───────────────────────────────────────────────────────────────
run_cdc() {
  echo "──────── CDC(DIRECT) ────────"
  local pid; pid=$(uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || python3 -c "import uuid; print(uuid.uuid4())")
  local body
  body=$(jq -nc \
    --arg pid "$pid" --arg pk "$PROJECT_KEY" \
    --arg seng "$SRC_ENGINE" --arg sh "$SRC_HOST" --argjson sp "$SRC_PORT" \
    --arg sdb "$SRC_DB" --arg ssc "$SRC_SCHEMA" --arg stb "$SRC_TABLE" --arg ssr "$SRC_SECRET_REF" \
    --arg keng "$SINK_ENGINE" --arg kh "$SINK_HOST" --argjson kp "$SINK_PORT" \
    --arg kdb "$SINK_DB" --arg ksr "$SINK_SECRET_REF" \
    '{pipelineId:$pid, projectKey:$pk, pattern:"DIRECT",
      source:{engine:$seng,host:$sh,port:$sp,dbName:$sdb,schema:$ssc,table:$stb,secretRef:$ssr},
      sink:{engine:$keng,host:$kh,port:$kp,dbName:$kdb,schema:null,table:null,secretRef:$ksr}}')
  info "POST /internal/pipelines (CDC) pipelineId=${pid}"
  post_pipeline "${body}" >/dev/null || return 1
  wait_running "${pid}-source" || return 1
  wait_running "${pid}-sink" || return 1
  expect_topic "cdc.table.${PROJECT_KEY}.${SRC_DB}.${SRC_SCHEMA}.${SRC_TABLE}" || return 1
  pass "CDC E2E GREEN"
}

# ── main ──────────────────────────────────────────────────────────────────────
preflight
rc=0
case "${SCENARIO}" in
  eda) run_eda || rc=1 ;;
  cdc) run_cdc || rc=1 ;;
  all) run_eda || rc=1; run_cdc || rc=1 ;;
  *)   fail "알 수 없는 시나리오: ${SCENARIO} (eda|cdc|all)"; exit 2 ;;
esac

echo "──────────────────────────────"
if (( rc == 0 )); then
  pass "SMOKE PASSED (${SCENARIO})"
else
  fail "SMOKE FAILED (${SCENARIO})"
fi
exit "${rc}"
