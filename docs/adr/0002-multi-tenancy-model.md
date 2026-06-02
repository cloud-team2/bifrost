# ADR 0002: 멀티 테넌시 모델

**Status**: Accepted (2026-06-02 design 정렬)
**Date**: 2026-05-15

> 용어·네이밍을 설계 문서([design/overview.md](../README.md), [springboot/DETAILS.md §2 Provisioning](../design/backend-springboot.md))에 맞춰 갱신했다. 테넌트 단위는 **워크스페이스(workspace)** 이며, 내부 운영 API는 같은 테넌트를 `project_id`로 부른다(v1에서 `workspace_id` = `project_id`, uuid). 사람이 읽는 Kafka 리소스 이름은 워크스페이스 생성 시 발급하는 슬러그 **`projectKey`** 를 기준으로 한다.

## Context

여러 워크스페이스가 같은 Kafka 클러스터를 공유하는 멀티 테넌시 플랫폼이 필요. 격리 수준에 따라 3가지 모델 가능:
- A. 공유 클러스터 (Soft) — Topic prefix + ACL로 격리
- B. 전용 클러스터 (Hard) — 워크스페이스마다 별도 Kafka cluster
- C. 하이브리드 — 등급별 분리

## Decision

**Phase 1: 모델 A (공유 클러스터 + Soft Multi-Tenancy)**

격리 메커니즘:
- 공유 Kafka 클러스터 `platform-kafka` (KRaft), 네임스페이스 `platform-kafka`
- Topic prefix 강제: `cdc.table.{projectKey}.{dbName}.{schema}.{table}` (Debezium `topic.prefix = cdc.table.{projectKey}.{dbName}`)
- 워크스페이스 단위 KafkaUser `proj-{projectKey}-user`: ACL은 `cdc.table.{projectKey}.*` prefix에 대해서만 read/write
- Strimzi가 KafkaUser의 SCRAM 자격증명 Secret을 자동 생성 (워크스페이스의 모든 Connector가 참조)
- source/sink DB 자격증명은 별개로 `secret_ref`(K8s Secret/Secrets Manager)로 보관 — 메타DB에 평문·암호문 저장 금지

**Phase 2 이후**: 등급별로 전용 클러스터 옵션 추가 (모델 C로 진화)

## Consequences

### 긍정적
- 운영 단순 (클러스터 1개)
- 비용 효율
- 신규 워크스페이스 즉시 사용 가능

### 부정적
- Noisy neighbor 가능성 (한 워크스페이스 부하가 다른 워크스페이스 영향)
- 보안 격리가 ACL에만 의존
- 컴플라이언스 강한 케이스 (예: 금융) 만족 어려움

## Mitigation

- Kafka Broker Quotas로 처리량 제한
- 토픽/Connector 수 한도 관리
- Phase 2에 전용 클러스터 옵션 제공
