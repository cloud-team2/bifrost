#!/usr/bin/env bash
# EKS 배포 후 ai-service + agentdb 검증 자동화.
#
# 환경변수:
#   KUBECONFIG (필수): EKS kubeconfig 경로
#   AGENTDB_NAMESPACE (기본 'agentdb')
#   AI_NAMESPACE (기본 'bifrost-system')
#   AI_SERVICE_HOST (필수, 예: https://ai-service.bifrost.skala-ai.com)
#
# 사용:
#   KUBECONFIG=~/.kube/config-skala_student \
#   AI_SERVICE_HOST=https://ai-service.bifrost.skala-ai.com \
#   bash scripts/verify-eks-deploy.sh

set -euo pipefail

AGENTDB_NAMESPACE="${AGENTDB_NAMESPACE:-agentdb}"
AI_NAMESPACE="${AI_NAMESPACE:-bifrost-system}"
AI_SERVICE_HOST="${AI_SERVICE_HOST:?AI_SERVICE_HOST required}"

pass() { echo "✅ $1"; }
fail() { echo "❌ $1"; exit 1; }

command -v kubectl >/dev/null 2>&1 || fail "kubectl not found"
command -v curl >/dev/null 2>&1 || fail "curl not found"
command -v jq >/dev/null 2>&1 || fail "jq not found"
[[ -n "${KUBECONFIG:-}" ]] || fail "KUBECONFIG required"

echo "=== 1. agentdb 마이그레이션 적용 확인 ==="
AGENTDB_POD=$(kubectl get pod -n "$AGENTDB_NAMESPACE" -l app=agentdb -o jsonpath='{.items[0].metadata.name}')
[[ -n "$AGENTDB_POD" ]] || fail "agentdb pod not found"
VERSION=$(kubectl exec -n "$AGENTDB_NAMESPACE" "$AGENTDB_POD" -- psql -U agent -d agentdb -tA -c "SELECT version_num FROM alembic_version;" 2>&1 | tail -1 | tr -d '\r')
echo "alembic_version: $VERSION"
[[ "$VERSION" == "005" || "$VERSION" == "006" ]] && pass "alembic head 005 또는 006" || fail "alembic_version != 005/006 (현재: $VERSION)"

TABLES=$(kubectl exec -n "$AGENTDB_NAMESPACE" "$AGENTDB_POD" -- psql -U agent -d agentdb -tA -c "\\dt" 2>&1)
for t in agent_run state_patch run_event knowledge_chunk report_snapshot change_ticket; do
  echo "$TABLES" | grep -q "$t" && pass "table $t exists" || fail "table $t missing"
done

USER_MSG_COL=$(kubectl exec -n "$AGENTDB_NAMESPACE" "$AGENTDB_POD" -- psql -U agent -d agentdb -tA -c "SELECT column_name FROM information_schema.columns WHERE table_name='agent_run' AND column_name='user_message';" 2>&1)
echo "$USER_MSG_COL" | grep -q "user_message" && pass "agent_run.user_message column exists" || fail "agent_run.user_message missing"

echo ""
echo "=== 2. ai-service 기동 로그 확인 ==="
AI_POD=$(kubectl get pod -n "$AI_NAMESPACE" -l app=ai-service -o jsonpath='{.items[0].metadata.name}')
[[ -n "$AI_POD" ]] || fail "ai-service pod not found"
LOGS=$(kubectl logs -n "$AI_NAMESPACE" "$AI_POD" --tail=200)
echo "$LOGS" | grep -q "agentdb unavailable" && fail "agentdb unavailable 메시지 출력됨" || pass "agentdb unavailable 미출력"
echo "$LOGS" | grep -qi "uvicorn.*started\|Application startup complete" && pass "uvicorn 정상 기동" || echo "⚠️  uvicorn started 로그 미발견 (확인 권장)"

echo ""
echo "=== 3. /ready 응답 확인 ==="
READY=$(curl -sS "$AI_SERVICE_HOST/api/v1/ready")
echo "$READY" | jq
echo "$READY" | jq -e '.data.dependencies.agent_run_store == "ok"' > /dev/null && pass "agent_run_store=ok" || fail "agent_run_store != ok"
echo "$READY" | jq -e '.data.dependencies.spring_operations == "ok" or .data.dependencies.spring_operations == "unavailable"' > /dev/null && pass "spring_operations 응답 정상" || fail "spring_operations 응답 비정상"

echo ""
echo "=== 4. run 영속화 e2e ==="
RUN_RESPONSE=$(curl -sS -X POST "$AI_SERVICE_HOST/api/v1/agent/runs" \
  -H "Content-Type: application/json" \
  -d '{"project_id":"11111111-2222-3333-4444-555555555555","mode":"simple_query","message":"DLQ가 뭐야?"}')
RUN_ID=$(echo "$RUN_RESPONSE" | jq -r '.data.run_id')
echo "RUN_ID: $RUN_ID"
[[ -n "$RUN_ID" && "$RUN_ID" != "null" ]] || fail "run_id missing from POST /runs response"

SSE_LOG="/tmp/sse-$RUN_ID.log"
trap 'rm -f "$SSE_LOG"' EXIT

# SSE 완료 대기
curl -sS --max-time 20 "$AI_SERVICE_HOST/api/v1/agent/runs/$RUN_ID/events" > "$SSE_LOG"
grep -q "run_completed" "$SSE_LOG" && pass "run_completed 발행" || fail "run_completed 미발행"

# Pod 재시작
echo "  → ai-service Pod 재시작 중..."
kubectl rollout restart -n "$AI_NAMESPACE" deployment/ai-service
kubectl rollout status -n "$AI_NAMESPACE" deployment/ai-service --timeout=120s

# 재조회
RECHECK=$(curl -sS "$AI_SERVICE_HOST/api/v1/agent/runs/$RUN_ID")
STATUS=$(echo "$RECHECK" | jq -r '.data.status')
echo "재조회 status: $STATUS"
[[ "$STATUS" == "completed" ]] && pass "재시작 후 run 영속화 OK" || fail "재시작 후 run status != completed (현재: $STATUS)"

echo ""
echo "🎉 모든 검증 통과 — #143 (배포 후) 4건 close 가능"
