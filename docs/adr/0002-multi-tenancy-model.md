# ADR 0002: 멀티 테넌시 모델

**Status**: Accepted
**Date**: 2026-05-15

## Context

여러 테넌트가 같은 Kafka 클러스터를 공유하는 멀티 테넌시 플랫폼이 필요. 격리 수준에 따라 3가지 모델 가능:
- A. 공유 클러스터 (Soft) — Topic prefix + ACL로 격리
- B. 전용 클러스터 (Hard) — 테넌트마다 별도 Kafka cluster
- C. 하이브리드 — 등급별 분리

## Decision

**Phase 1: 모델 A (공유 클러스터 + Soft Multi-Tenancy)**

격리 메커니즘:
- K8s Namespace 단위 분리 (`tenant-{id}`)
- Topic prefix 강제 (`tenant-{id}.{schema}.{table}.cdc`)
- KafkaUser ACL: 자기 prefix만 read/write
- ResourceQuota로 자원 한도
- NetworkPolicy로 cross-tenant 차단

**Phase 2 이후**: 등급별로 전용 클러스터 옵션 추가 (모델 C로 진화)

## Consequences

### 긍정적
- 운영 단순 (클러스터 1개)
- 비용 효율
- 신규 테넌트 즉시 사용 가능

### 부정적
- Noisy neighbor 가능성 (한 테넌트 부하가 다른 테넌트 영향)
- 보안 격리가 ACL에만 의존
- 컴플라이언스 강한 케이스 (예: 금융) 만족 어려움

## Mitigation

- Kafka Broker Quotas로 처리량 제한
- ResourceQuota로 토픽/Connector 수 제한
- Phase 2에 전용 클러스터 옵션 제공
