# ADR 0003: 서비스 경계

**Status**: Accepted
**Date**: 2026-05-15

## Decision

4개 서비스로 분리:

| 서비스 | 책임 | 외부 노출 |
| --- | --- | --- |
| core-service | 인증, 도메인, REST API, WebSocket | ✅ |
| orchestrator-service | K8s/Kafka 자동화 | ❌ |
| ai-service | LLM 통합 (Sprint 4) | ❌ |
| frontend | React UI | ✅ |

### 통신
- core → orchestrator: 동기 REST (작업 트리거)
- orchestrator → core: Kafka 이벤트 (상태 변화 알림)
- core → ai-service: SSE 스트리밍 (채팅)

### 인증
- 외부: Browser → core, JWT 검증
- 내부: NetworkPolicy로 격리, 별도 인증 없음 (Phase 2에 mTLS 검토)

### Inspector 모듈
core 안의 모듈로 내장 (별도 서비스 X). DB 추상화 인터페이스 + PG/MariaDB 구현체.

### MCP 서버
ai-service 안의 도구 정의로 통합 (별도 서비스 X). Phase 2에서 외부 노출 시 분리.

## Rationale

- 명확한 책임 분리 (도메인 vs K8s 자동화 vs LLM)
- LLM의 외부 의존성 격리 (장애 전파 차단)
- 5명 팀의 캡스줍 일정에 현실적인 분리 수준
