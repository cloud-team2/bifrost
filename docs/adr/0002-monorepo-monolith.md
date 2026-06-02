# ADR 0002: 모노레포 + Spring Boot 모놀리스 채택

**Status**: Accepted
**Date**: 2026-06-02
**Supersedes**: [ADR 0001 (폴리레포)](./0001-polyrepo.md)

## Context

[ADR 0001](./0001-polyrepo.md)은 7개 폴리레포 + 서비스별 MSA(core / orchestrator / ai 분리)를 결정했다. 그러나 실제 작업은 단일 레포(`bifrost`) 안에서 진행되었고, 설계 문서([design/overview.md](../../../skala/design/overview.md) §2, [springboot/DETAILS.md](../../../skala/design/backend/springboot/DETAILS.md) §5)는 백엔드를 **두 개의 백엔드**로만 정의한다.

```
Frontend ─┬─► Spring Boot Operations Backend  (플랫폼 본체, 단일 모놀리스)
          └─► FastAPI Agent Server ──► Spring(/internal/ops)
```

즉 설계상 Spring Boot는 처음부터 **단일 모놀리스**(`com.bifrost.ops`)이고, 현재 레포의 `core-service` / `orchestrator-service` 분리는 설계에 없는 분리다. 6주 캡스톤·5인 팀 규모에서 MSA의 격리 이득보다 서비스 간 인증/네트워킹/배포 ×2 비용이 크다. 특히 설계의 `PipelineStatusService`(상태 변경 단일 writer)는 watcher가 in-process로 직접 호출하는 구조라, core ↔ orchestrator 분리 시 불필요한 cross-service 호출이 생긴다.

## Decision

1. **모노레포** 유지 (폴리레포 폐기).
2. **Spring Boot는 단일 모놀리스** `services/operations-backend`로 통합한다.
   - `core-service` + `orchestrator-service` → `services/operations-backend` 병합.
   - base 패키지 `com.platform.*` → **`com.bifrost.ops`** 통일.
   - 패키지 구조는 설계 [springboot/DETAILS.md §5](../../../skala/design/backend/springboot/DETAILS.md)를 따른다.
3. **Agent는 FastAPI(Python)** 로 별도 서비스 유지(`services/ai-service`). Spring Boot의 `/internal/ops`만 호출하며 K8s/Kafka에 직접 접근하지 않는다. (현재 Java 스캐폴드 → FastAPI 전환은 별도 이슈)
4. **`libs/common-dto` 흡수** — 모놀리스 내부 패키지로 옮기고 Gradle 모듈 제거. Spring ↔ FastAPI 계약은 공유 lib 대신 `/internal/ops` HTTP(JSON)/OpenAPI로 관리(언어가 갈리므로 공유 jar 불가).

## Consequences

### 긍정적
- 서비스 간 호출/인증 경계 제거, `PipelineStatusService` in-process 단일 writer 그대로 구현.
- 단일 빌드·배포물(Dockerfile/Helm 1개), 통합 테스트 단순화.
- 설계 문서(§2, §5)와 코드 구조 일치.

### 부정적
- 단일 모듈이 커진다(패키지 경계로 책임 분리: database/provisioning/operations/...).
- 독립 배포 불가(모놀리스 특성). v1 규모에서는 수용.

## 관련 작업
- 이슈 #20 (모놀리스 병합), 이슈 B (ai-service FastAPI 전환).
- 충돌 메모: `chore/#14`의 "ADR 0001 도메인 모델 정렬"(기존 스캐폴드 유지 + add-only)은 본 ADR과 방향이 다르므로, 머지 시 본 결정 기준으로 재조정 필요.

## References
- 설계: [design/overview.md](../../../skala/design/overview.md) §2, §5
- 설계: [design/backend/springboot/DETAILS.md](../../../skala/design/backend/springboot/DETAILS.md) §1, §5
