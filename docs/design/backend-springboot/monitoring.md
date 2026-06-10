# Spring Boot Operations Backend — Monitoring & Incident Engine

> 요약은 [overview.md](./overview.md). 이 파일은 **관측 데이터 수집 → 상태 산정 → 이벤트 기록/SSE 발행 → IncidentService 현재 연결 상태**와 모니터링 read(Sync/Messages/Metrics) 구현을 다룬다. 상태값·임계값·이벤트→인시던트 목표 규칙의 **정본은 [기능명세서 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)** 이며, 이 문서는 현재 구현과 미연결 지점을 함께 기록한다.
>
> 패키지: `monitoring`(query·collector) · `event` · `incident` ([server.md §5](./server.md#5-패키지-구조)). 현재 플랫폼 pipeline detail read는 `pipeline` domain service(`PipelineSyncService`/`PipelineTopicService`/`PipelineMessageService`)를 직접 호출하고, agent `/internal/ops` observability read는 `InternalOpsObservabilityController`가 `AdminClient`/`LokiClient`/`JdbcTemplate`/`IncidentRepository`/`RestClient`를 직접 주입한다. `monitoring.query` 공유 port는 아직 코드 구조로 정착되어 있지 않다.

## 6. Monitoring and Incident Engine

### 1. 목적·범위

부록 B를 코드로 구현하는 계층. 두 흐름으로 나뉜다.

```text
[수집] Watcher(상태 event) + 폴링 수집기(지표) + 쿼리 어댑터(metric/log/trace)
   → [상태 산정] raw → pipeline.status / connector.state / db.health / cg.state (부록 B.1~B.4)
   → [이벤트 엔진] event row 생성 (부록 B.6 일부)
   → [인시던트 엔진] 현재 poller와 자동 생성 경로는 미연결
   → [SSE] pipeline_status_changed · connector_state_changed(Incident SSE는 수동 incident path 기준)
[read]  Sync(FR-009) · Messages(FR-010) · DB Metrics(FR-017) 는 요청 시 query
```

커버 FR: 모니터링 read(FR-006~009·017·020·023), 이벤트/인시던트(FR-019·021·024·026 탐지측 일부). 현재 `event`·`incident` 쓰기는 각 도메인 서비스(`EventService`/`IncidentService`)를 통과하지만, `pipeline.status`는 자동 전이는 `PipelineStatusService`, 사용자 pause/resume은 `PipelineService`가 저장한다.

### 2. 수집 계층 (collectors)

상태 전이는 Watcher(event-driven), 지표는 주기 폴링, metric/log/trace는 on-demand 질의로 모은다. 주기·소스의 정본은 부록 B.6 각 표(헤더에 명시)다.

| 수집기 | 방식·주기 | 소스 | 산출(요약) |
| --- | --- | --- | --- |
| **ConnectorWatcher** | watch(event) | Fabric8 `KafkaConnector .status` | connector/task state 전이 → `PipelineStatusService` ([provisioning §6](./provisioning.md#2-provisioning)) |
| **KafkaAdminPoller** | 30s | Kafka AdminClient | sink connector consumer lag을 조회해 threshold event를 기록한다. topic metadata/partition catalog 수집은 현재 수행하지 않는다 |
| **ConnectRestPoller** | 10s | Connect REST `GET /connectors/{n}/status` | task failure/recovery event 기록. connector state 저장·pipeline 재계산은 현재 수행하지 않는다 |
| **JmxPoller** | 60s | Prometheus `PrometheusClient`/PromQL | worker JVM heap·cpu·gc. connector-task poll batch·records/sec·error rate는 현재 수집하지 않는다 |
| **DatabaseHealthProbeJob** | 60s | source/sink DB(동적 DataSource) | `connection_status`를 `HEALTHY` 또는 `UNREACHABLE`로 갱신, 영향 pipeline 재계산. 미점검 row는 nullable 상태다 |
| **쿼리 어댑터** | on-demand | Prometheus/Loki/Connect REST | metric/log/trace 근거. 현재 `query_traces`는 Tempo가 아니라 Connect REST task `trace` field를 노출한다 |

구현 메모:
- 폴링 수집기는 Spring `@Scheduled`(또는 ShedLock 분산 락) + project별 fan-out. 한 주기 실패가 다른 project를 막지 않도록 per-resource try/catch.
- 폴링 결과는 전이가 있을 때 event row를 만든다. 현재 `KafkaAdminPoller`와 `ConnectRestPoller` 경로는 `EventService.record(...)`만 호출하며 SSE를 직접 발행하지 않는다.
- Watcher가 connector/pipeline 상태 전이의 1차 소스다. 현재 poller는 lag/지표/이벤트성 신호를 기록하며, connector state를 `connectors` table이나 pipeline status로 reconcile하지 않는다.

### 3. 상태 산정 (status derivation)

raw 수집값 → 상태 enum 매핑. **정의는 부록 B, 이 절은 산정 위치만 고정**한다.

| 대상 | 결과 enum | 산정기 | 정본 |
| --- | --- | --- | --- |
| pipeline | API 결과 enum `creating`/`active`/`lag`/`error`/`paused` (DB 저장값은 upper-case `PipelineLifecycle` enum name) | `PipelineStatusService.recompute(pipelineId)` | [B.1](../../spec.md#b1-pipeline-상태값) |
| connector | `RUNNING`/`PARTIALLY_FAILED`/`FAILED`/`PAUSED`/`UNASSIGNED`/`UNKNOWN` | Watcher. `ConnectRestPoller`는 현재 task event 기록만 수행 | [B.2](../../spec.md#b2-connector-인스턴스-상태값) |
| database | `HEALTHY`/`UNREACHABLE`/`UNKNOWN` (`connection_status`) | `DatabaseHealthProbeJob`/`MonitoringReadService` → `datasources.connection_status` | 현재 code/migration |
| consumer group | Kafka `ConsumerGroupDescription.state().toString()` raw 값 | `PipelineTopicService.fetchConsumerGroups(...)` on-demand 조회. `KafkaAdminPoller`는 lag event만 기록한다 | 현재 code |

- **`PipelineStatusService.recompute`**: 현재 `PipelineStatusServiceImpl`은 connector state 집합과 source/sink DB reachability를 입력으로 상태를 계산한다. consumer lag/error rate는 현재 `recompute` 입력이 아니다. 우선순위는 connector `FAILED`→`error`, `PARTIALLY_FAILED`→`lag`, `PAUSED`→`paused`, 기대 connector 수만큼 `RUNNING`→`active`, 그 외→`creating`; DB가 `UNREACHABLE`이면 `creating`이 아닌 pipeline은 `error`다.
- **DB 상태의 파이프라인 전이(#179)**: `database`는 등록 1회가 아니라 `DatabaseHealthProbeJob`(60s)으로 주기 프로브하며, `UNREACHABLE`이 되면 `reevaluateForDatasource`로 해당 파이프라인을 `error`로 전이한다(원인 메시지 포함). connector가 RUNNING이어도 source/sink DB가 끊기면 파이프라인은 error다. 정본 [lifecycle.md §2·§5](./lifecycle.md).
- **lag/미동기화 메트릭은 consumer group으로 필터(#200)**: `kafka_consumergroup_lag`를 토픽으로 합산하면 같은 토픽을 구독하던 **삭제된 파이프라인의 orphan group**까지 더해진다. 해당 파이프라인 sink group(`connect-<pid>-sink`)으로만 필터해야 정확하다.
- **데이터 전송 시간(소스 지연)**: `millisecondsbehindsource`는 순간 게이지라 부하 타이밍에 따라 크게 튀므로 `avg_over_time`(≥60s)으로 평활화해 추세를 보인다(#200). 측정 불가(idle)는 `-1` → 프론트가 그래프 gap 처리.
- pipeline·connector·db는 metadb에 영속(전이 시 갱신), consumer group state는 live 조회(테이블 없음).

### 4. 이벤트 엔진 (`event`)

상태 전이·임계 초과를 `event` row로 적재한다. **카탈로그(트리거→레벨→인시던트 여부)의 정본은 [부록 B.6](../../spec.md#b6-이벤트-카탈로그)** 다.

흐름:

```text
collector/Watcher → 임계·전이 판정 → EventService.record(tenant, pipeline, level, type, message)
  → event row insert (level INFO/WARN/ERROR, type, pipeline_id, created_at)
```

`EventService.record(...)` 자체는 event row만 저장한다. SSE는 `SsePublisher`를 직접 호출하는 pipeline/connector/incident 경로에서 별도로 발행된다.

규칙:
- **중복 억제**: 현재 일부 경로는 중복 억제를 하지 않는다. 예를 들어 `DatabaseHealthProbeJob.probeReplicationLag()`는 lag가 threshold를 넘는 동안 매 poll마다 `DB_REPLICATION_LAG_WARNING` event를 기록할 수 있다.
- **category**: 현재 일반 event 기록은 category가 nullable이다. `IncidentService.onThresholdViolation(...)` 경로는 `sourceType`(`CONSUMER_GROUP`/`CONNECTOR`/`DATABASE`)을 그대로 category에 쓰고, recovery 경로는 null을 쓴다.
- **레벨→인시던트**: 현재 poller들은 `EventService.record(...)`만 호출하고 `IncidentService.onThresholdViolation(...)`을 호출하지 않는다. Incident 자동 생성 규칙은 구현 보강 대상이다.

### 5. 인시던트 엔진 (`incident`)

현재 `IncidentService`는 `onThresholdViolation(...)`/`onRecovery(...)` 메서드를 제공하지만 poller 경로에 연결되어 있지 않다. **목표 규칙은 [부록 B.7](../../spec.md#b7-인시던트-자동-생성-및-그룹화-규칙)**, 현재 데이터 모델은 [data-model §3.7](./data-model.md#4-data-model)을 따른다.

```text
IncidentService.onThresholdViolation(...):
  severity = event level 기반 WARN/ERROR
  기존 OPEN incident가 있으면 WARN→ERROR escalation 가능
  없으면 즉시 incident row 생성

openOrAttach(severity):
  key = groupingKey(event)                         # B.7 그룹화 키
  inc = findOpenIncidentByKey(key)
  if inc == null: inc = create(severity, grouping_key=key)
  else:           escalateIfNeeded(inc, severity)  # WARN+ERROR → ERROR
  SSE incident_opened|incident_updated
```

- **그룹화 키**(B.7): 동일 Source DB 다수 Pipeline FAILED→`source_db_id` · 동일 Worker 다수 connector→`worker_id` · 동일 CG lag+REBALANCING→`consumer_group` · 연쇄 replication lag→pipeline lag→`source_db_id`.
- **그룹 멤버십**: `events.incident_id` 컬럼은 존재하지만 현재 poller→incident 자동 연결 경로는 구현되어 있지 않다. `incident`에는 `trigger_event_id` 컬럼이 없고 event timestamp는 `created_at`이다([data-model §3.7](./data-model.md#4-data-model)).
- **severity**: 현재 `IncidentService`는 생성 시 WARN event→`WARN`, ERROR event→`ERROR`를 저장하고, 기존 `WARN` incident에 ERROR event가 붙으면 `ERROR`로 올린다.
- **auto-resolve**: `IncidentService.onRecovery(...)`는 같은 grouping key의 `OPEN` incident를 `RESOLVED`로 닫는다.
- **멱등 생성**: 같은 key의 open 인시던트가 있으면 새로 만들지 않고 attach한다. 현재 DB migration은 `incidents(tenant_id, grouping_key)` non-unique index만 만들며 부분 unique 제약은 없다.
- **RCA 기록(FR-026)**: 현재 controller-mapped RCA write route는 없다. `IncidentService.updateRca()` 내부 메서드는 `rca` field만 저장하며 severity 보정/report reference 기록은 하지 않는다.

### 6. 파생 read — Sync / Messages / DB Metrics

요청 시 계산하는 조회(폴링 영속 아님).

- **Sync (FR-009)**: 현재 `PipelineSyncService`는 source/sink DB에 각각 `SELECT COUNT(*)`를 실행해 `sourceRows`, `sinkRows`, `delta`, `checkedAt`을 반환한다. 접속 실패·테이블 미존재는 `-1`로 반환한다.
- **Messages (FR-010)**: 토픽 **최근 N건 bounded consume**(`auto.offset.reset` 영향 없는 일회성 consumer로 end-N..end 범위 read) → Debezium before/after 표시. Topic 이름은 alias로만 노출([FR-010](../../spec.md#fr-010--토픽-메시지-조회)).
- **DB Metrics (FR-017)**: 현재 `/databases/{id}/metrics`는 stub 응답이다. 응답 field는 `tps`, `queryResponseMs`, `activeConnections`, `stub`이며 placeholder 값은 `0.0`, `0.0`, `0`, `true`다. inspector 기반 엔진별 stat 질의는 아직 연결되어 있지 않다.

### 7. SSE 발행 (`streaming`)

플랫폼 SSE(workspace 범위·장수명)로 상태 변화를 push한다(Agent run SSE와 별도 — [frontend](../frontend.md)).

| 이벤트 | 트리거 | 소비 |
| --- | --- | --- |
| `pipeline_status_changed` | `PipelineStatusService.recompute` 전이 | PipelinesView·상세 |
| `connector_state_changed` | `PipelineStatusServiceImpl.applyConnectorStatus` 전이(Watcher 경로) | 상세 Connector 탭(토글) |
| `incident_opened` / `incident_updated` | `IncidentService` 생성·갱신 | 사이드바 배지·AlertsView |

엔드포인트 `GET /api/v1/workspaces/{wsId}/events/stream`([Spring Boot API: Workspace Event Stream](../../api/springboot.md#workspace-event-stream)). `EventSource`는 헤더 불가 → JWT `?access_token=` 쿼리.

### 8. 플랫폼 read API 매핑

| FR | 화면 | API([Spring Boot API Reference](../../api/springboot.md)) | 데이터 출처 |
| --- | --- | --- | --- |
| FR-006 | Overview | EDA: `/pipelines/{id}/topic-info`; CDC: `/pipelines/{id}/sync-status` + `/pipelines/{id}/metrics/event-distribution?minutes=15` | 현재 frontend Overview 구현은 `PipelineDetail`에서 EDA는 `TopicTab`, CDC는 `SyncTab`을 재사용한다. `/pipelines/{id}/metrics` API는 남아 있지만 Overview 탭에서 호출하지 않는다 |
| FR-007 | Consumers | `/pipelines/{id}/consumer-groups` | KafkaAdmin(B.4) |
| FR-008 | Connector | `/pipelines/{id}/connectors` | `PipelineService.listConnectors`가 connector table을 조회한다. table 값은 Watcher 경로가 갱신한다 |
| FR-009 | Sync | `/pipelines/{id}/sync-status` | `PipelineSyncService.syncStatus(...)` |
| FR-010 | Messages | `/pipelines/{id}/messages` | §6 bounded consume |
| FR-017 | DB Metrics | `/databases/{id}/metrics` | 현재 stub 응답 |
| FR-019 | Activity | `/events` | `event` 테이블 |
| FR-020 | Overview(운영) | `/overview` | 집계 query |
| FR-021 | Alerts | `/incidents`·`/incidents/{id}` | `IncidentService.list/get`의 incident row. `IncidentResponse`에는 event 역참조 목록이 없다 |
| FR-023 | Cluster | `/cluster` | KafkaAdmin broker + JMX worker |
| FR-024 | Resource events | `/resource-events` | 현재 `MonitoringReadService.resourceEvents(...)`는 `AdminClient.listPartitionReassignments()` 기반 `PARTITION_REASSIGNMENT`만 반환한다 |

### 9. 구현 메모

- **스케줄러·분산 락**: 다중 replica에서 폴링 중복을 막으려면 ShedLock 등으로 collector를 단일 실행. SSE fan-out·run 잠금이 필요하면 Redis(선택).
- **writer 경계**: `event`·`incident` 변경은 각 서비스로 직렬화한다. `pipeline.status`는 자동 전이는 `PipelineStatusService`, 사용자 pause/resume은 `PipelineService`가 직접 저장한다.
- **diff/중복 억제 상태**: 직전 스냅샷(메모리 캐시 또는 last_* 컬럼)으로 "전이/최초/복구"만 이벤트화.
- **retention**: `event`는 보존 정책으로 아카이브, 인시던트는 resolved 후 일정 기간 유지.
- **테스트 기준**: (a) 임계 경계값에서 정확히 한 번 이벤트 생성, (b) 동일 트리거 반복 시 중복 인시던트 미생성(멱등), (c) WARNING→ERROR 에스컬레이션, (d) 복구 시 auto-resolve 권고, (e) EDA는 lag 인시던트 미생성, (f) SSE 전이 1:1.
