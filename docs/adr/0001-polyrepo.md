# ADR 0001: 폴리레포 채택

**Status**: Superseded by [ADR 0002](./0002-monorepo-monolith.md) (2026-06-02)
**Date**: 2026-05-15

> ⚠️ 이 결정은 [ADR 0002](./0002-monorepo-monolith.md)로 대체되었다. 모노레포 + Spring Boot 모놀리스로 전환했다. 아래 내용은 이력으로 보존한다.

## Context

5명 팀이 6주 동안 4개 백엔드 서비스(core, orchestrator, ai) + Frontend + Infra를 만든다. 레포 구조는 모노레포와 폴리레포 두 가지 선택지가 있다.

## Decision

폴리레포로 간다. 7개 레포 구성:

- platform-core-service
- platform-orchestrator-service
- platform-ai-service
- platform-frontend
- platform-infra
- platform-common-dto
- platform-docs

공유 DTO는 `platform-common-dto`에 두고 GitHub Packages로 SNAPSHOT 발행.

## Consequences

### 긍정적
- 서비스별 책임 명확
- 각자 자기 레포 권한 관리
- 서비스별 독립 배포

### 부정적
- 공유 DTO 관리 부담 (publish, version)
- 호환성 깨지는 변경 시 동시 PR 필요
- 통합 테스트 어려움 (각 레포에서 mock 또는 별도 E2E)

## Mitigation

- common-dto는 SNAPSHOT 버전으로 운영 (매 머지마다 publish, 의존 레포는 자동 가져감)
- BREAKING CHANGE 시 Slack 알림 + 동시 PR 절차
- 로컬 docker-compose로 통합 검증
