#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

for bin in curl jq docker python3; do
  command -v "$bin" >/dev/null 2>&1 || {
    echo "missing required command: $bin"
    exit 1
  }
done

cleanup() {
  docker compose rm -sfv ai-service wiremock agentdb >/dev/null 2>&1 || true
  docker compose down
}
trap cleanup EXIT

docker compose rm -sfv ai-service wiremock agentdb >/dev/null 2>&1 || true
docker compose up -d --build agentdb wiremock ai-service

for i in {1..60}; do
  if curl -fs http://localhost:8082/api/v1/health >/dev/null 2>&1; then
    echo "ai-service ready (attempt $i)"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "ai-service health check timed out"
    docker compose logs ai-service
    exit 1
  fi
  sleep 1
done

echo "=== /ready response ==="
curl -sS http://localhost:8082/api/v1/ready | jq

RESPONSE=$(curl -sS -X POST http://localhost:8082/api/v1/agent/runs \
  -H "Content-Type: application/json" \
  -d '{
    "project_id":"11111111-2222-3333-4444-555555555555",
    "mode":"incident_analysis",
    "message":"consumer lag 급증 lag p95 증가 offset progression 정체 로그 메트릭 커넥터 trace",
    "remediation_requested": true
  }')
if ! echo "$RESPONSE" | jq -e . >/dev/null 2>&1; then
  echo "run creation returned non-JSON response:"
  echo "$RESPONSE"
  docker compose logs ai-service
  exit 1
fi
RUN_ID=$(echo "$RESPONSE" | jq -r '.data.run_id')
if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
  echo "failed to create run"
  echo "$RESPONSE" | jq
  exit 1
fi
echo "RUN_ID: $RUN_ID"

SSE_LOG="/tmp/sse-$RUN_ID.log"
curl -sS --max-time 25 "http://localhost:8082/api/v1/agent/runs/$RUN_ID/events" > "$SSE_LOG" || true

echo "=== assertions ==="

COMPLETED=$(grep -c 'event: tool_call_completed' "$SSE_LOG" || true)
FAILED=$(grep -c 'event: tool_call_failed' "$SSE_LOG" || true)
echo "tool_call_completed: $COMPLETED"
echo "tool_call_failed: $FAILED"
[ "$COMPLETED" -ge 2 ] || { echo "tool_call_completed < 2"; exit 1; }
[ "$FAILED" -eq 0 ] || { echo "tool_call_failed > 0"; exit 1; }

grep -q "CONSUMER_LAG_SPIKE" "$SSE_LOG" || { echo "Classifier did not emit CONSUMER_LAG_SPIKE"; exit 1; }

RCA_CONFIDENCE=$(grep 'event: report_preview_available' -A1 "$SSE_LOG" | python3 -c "import sys,json; confidence=0
for line in sys.stdin:
    if line.startswith('data: '):
        payload=json.loads(line[6:])
        confidence=payload.get('payload', {}).get('confidence') or 0
        break
print(confidence)")
echo "RCA confidence: $RCA_CONFIDENCE"
python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) >= 0.80 else 1)" "$RCA_CONFIDENCE" || {
  echo "RCA confidence < 0.80"
  exit 1
}

grep -q 'event: run_completed' "$SSE_LOG" || { echo "run_completed not emitted"; exit 1; }

echo "All assertions passed"
