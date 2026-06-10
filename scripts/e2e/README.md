# incident_analysis e2e smoke

## 목적

Spring 실 응답 가정 환경에서 incident_analysis full chain 동작 검증.

## 실행

```bash
AI_LLM_API_KEY=sk-... bash scripts/e2e/incident_analysis_smoke.sh
```

## 환경

- docker compose: agentdb (pgvector) + wiremock + ai-service
- WireMock 이 Spring `/internal/ops/...` 응답 stub (5종)
- agentdb host port는 기존 `meta-db`의 `5433`과 충돌하지 않도록 `5435` 사용

## 검증 항목

- /ready 5 dependency 응답
- tool_call_completed >= 2, tool_call_failed == 0
- Classifier CONSUMER_LAG_SPIKE
- RCA confidence >= 0.80
- run_completed
