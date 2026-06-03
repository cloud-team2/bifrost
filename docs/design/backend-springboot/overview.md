# Spring Boot Operations Backend 설계 (개요)

> 사람이 읽는 요약본이자 이 폴더의 진입점이다. 서버 설계·프로비저닝·DB 등록·데이터 모델은 [server.md](./server.md)·[provisioning.md](./provisioning.md)·[database-registry.md](./database-registry.md)·[data-model.md](./data-model.md), API 전체는 [api/springboot.md](../../api/springboot.md), 상태값·임계값은 [기능명세서 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처).

Bifrost의 **플랫폼 본체이자 운영 제어의 최종 집행자**. 한 서버가 두 역할을 한다.

1. **플랫폼(frontend-facing, `/api/v1`)**: 워크스페이스·Database·Pipeline CRUD, Kafka 리소스 프로비저닝, DB 등록·CDC 점검, 모니터링·이벤트 조회, 메타데이터 저장.
2. **운영 조치 실행(agent-facing, `/internal/ops`)**: FastAPI Agent의 action 후보를 정책·승인·감사·idempotency 검증 후 K8s/Kafka/Connect/Observability에 실행.

LLM·prompt·RCA 추론은 하지 않는다. 두 경로 모두 같은 정책·감사·프로비저닝 계층을 공유한다.

```mermaid
flowchart LR
    FE[Frontend] -->|/api/v1| P[플랫폼 API]
    AG[FastAPI Agent] -->|/internal/ops| O[내부 운영 API]
    P --> CORE
    O --> CORE
    subgraph SB[Spring Boot Operations Backend]
      CORE[정책·승인·변경관리·idempotency·감사] --> ADP[Resource Adapter]
    end
    ADP --> K8S[Fabric8 / Strimzi]
    ADP --> KAFKA[Kafka Admin / Connect REST]
    ADP --> OBS[Prometheus / Loki / Tempo]
    ADP --> META[(metadb)]
```

## 핵심 결정

| 항목 | 결정 |
| --- | --- |
| 식별자 | `workspace_id`=`project_id`(uuid, scope 검증) ≠ **`project_key`**(슬러그, Kafka 리소스 이름용, 이름에서 자동 생성) |
| 파이프라인 | **단일 테이블 1개**. EDA(`fan_out`, Source만) / CDC(`direct`, Source Debezium + Sink JDBC) |
| 토픽 | Debezium 자동 생성 `cdc.table.{project_key}.{dbName}.{schema}.{table}`(part 6/RF 3). Source `tasksMax=1`, Sink `tasksMax=3` upsert |
| DB 자격증명 | **secretRef만** 메타DB 저장(외부 Secret Store). 평문·암호문 금지. 생성 시점에만 `secretStore.resolve()` |
| 상태/관측 | **상태**=Fabric8 Watcher(event) / **지표·이벤트·인시던트**=폴링 수집기+Prometheus/Loki/Tempo 질의 → [monitoring.md](./monitoring.md) |
| 신뢰 경계 | FastAPI 판단을 믿지 않고 **실행 직전 재검증**. mutation은 approval/change·idempotency 없이 금지, 모든 요청 audit. **Approval·집행 allowlist 원본=Spring**([server.md §7.1](./server.md#71-operation-allowlist-집행-경계-단일-출처)) |
| 상태값·임계값 | [기능명세서 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)가 단일 출처(중복 정의 금지) |

## 메타데이터 DB (metadb) — ERD

**metadb**(`metadb` 네임스페이스 PostgreSQL — [Infra](../infra.md))는 플랫폼 **운영 메타데이터**만 둔다: `workspace`·`app_user`·`project_member` · `database`(`secret_ref`만) · `pipeline` · `connector` · `event` · `incident` · `audit_event` · `evidence_ref`. **고객 source/sink DB 데이터는 복제하지 않고**(메타데이터·지표·참조만), DB 자격증명·evidence 원문도 외부 저장소에 두고 참조만 보관한다. enum·임계값은 [부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처) 단일 출처.

> ERD·테이블 상세는 [data-model.md §4](./data-model.md#4-data-model).

## 패키지 (com.bifrost.ops)

**package-by-feature**: platform 도메인은 각자 `controller/service/repository/dto/entity`를 품고(응집), 전역 관심사는 `global`(config·common)로 묶는다. 모니터링 read·이벤트·인시던트는 `monitoring`/`event`/`incident`, 운영 조치 거버넌스는 `governance`로 묶고, agent-facing은 표면이 근본적으로 달라 `internalops`로 별도 분리한다.

`global(config·common) · auth · workspace · database(+cdc/inspector) · pipeline · provisioning(port/dto/mock/impl·watcher) · monitoring(query·collector) · event · incident · secret · streaming · internalops(agent-facing) · governance(policy·approval·changemanagement·idempotency·audit·evidence) · adapters(+port: kubernetes/kafka/connect/prometheus/...)`

> 각 platform 도메인(`workspace`/`database`/`pipeline`)은 내부에 `controller·service·repository·dto·entity`를 둔다. `internalops`는 여러 도메인을 가로지르고 인증·응답봉투·idempotency가 platform과 달라 한데 묶지 않는다. 상세는 [server.md §5 패키지 구조](./server.md#5-패키지-구조).

## 더 읽기

- [server.md](./server.md) — §1 Server Design (목적·책임·신뢰경계·계층·패키지·관측 수집·보안)
- [provisioning.md](./provisioning.md) — §2 Provisioning (Kafka/Connector CR 생성·watch)
- [database-registry.md](./database-registry.md) — §3 Database Registry (연결 테스트·secretRef·CDC 준비도)
- [data-model.md](./data-model.md) — §4 Data Model (metadb 스키마)
- [monitoring.md](./monitoring.md) — Monitoring & Incident Engine (수집기·상태 산정·이벤트/인시던트 자동화·Sync/Messages/Metrics·SSE)
- [api/springboot.md](../../api/springboot.md) — §5 API Reference (플랫폼 `/api/v1` + 내부 운영 `/internal/ops`)
