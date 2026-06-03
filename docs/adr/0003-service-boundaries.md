# ADR 0003: 서비스 경계

**Status**: Amended by [ADR 0004](./0004-monorepo-monolith.md) (2026-06-02)
**Date**: 2026-05-15

> ⚠️ [ADR 0004](./0004-monorepo-monolith.md)에 따라 `core-service` + `orchestrator-service`는 단일 모놀리스 `operations-backend`로 병합되었다. 아래 표/통신 항목을 그에 맞게 갱신했다.

## Decision

3개 서비스로 분리:

| 서비스 | 책임 | 외부 노출 |
| --- | --- | --- |
| operations-backend | 인증, 도메인, REST API, WebSocket, K8s/Kafka 자동화(provisioning·watcher) | ✅ (`/api/v1`) |
| ai-service | LLM 통합 / AI 장애대응 (FastAPI, W3 — [roadmap](../team/roadmap.md)) | ❌ |
| frontend | React UI | ✅ |

> 기존 core/orchestrator 분리는 폐기. K8s/Kafka 자동화는 operations-backend의 `provisioning`·`adapters` 패키지로 in-process 흡수된다.

### 통신
- provisioning ↔ watcher: **in-process** (단일 `PipelineStatusService` writer, cross-service 호출 없음)
- operations-backend → ai-service: SSE/HTTP (AI 장애대응)
- ai-service → operations-backend: `/internal/ops` (운영 조회·조치 위임)

### 인증
- 외부: Browser → core, JWT 검증
- 내부: NetworkPolicy로 격리, 별도 인증 없음 (Phase 2에 mTLS 검토)

### Inspector 모듈
core 안의 모듈로 내장 (별도 서비스 X). DB 추상화 인터페이스 + PG/MariaDB 구현체.

### MCP 서버
v1에서는 MCP Server를 두지 않는다([design/backend-fastapi/tool-catalog.md §5 MCP Decision](../design/backend-fastapi/tool-catalog.md#5-mcp-decision)). Agent는 Spring Boot `/internal/ops` + Tool Client Registry로만 운영을 위임한다(별도 MCP 서비스·endpoint·sidecar 없음). 필요 시 Phase 2 이후 read-only/proxy 용도로 재검토한다.

## Rationale

- 명확한 책임 분리 (도메인 vs K8s 자동화 vs LLM)
- LLM의 외부 의존성 격리 (장애 전파 차단)
- 5명 팀의 캡스줍 일정에 현실적인 분리 수준
