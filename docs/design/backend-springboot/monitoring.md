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
   → [인시던트 엔진] poller 신호 일부가 자동 생성 경로에 연결됨(상세 §5 [현재])
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
| **쿼리 어댑터** | on-demand | Prometheus/Loki/Connect REST | metric/log/trace 근거. ops-backend는 자체 작업 span을 OTLP로 Tempo에 송신한다(#366). `query_traces`는 아직 Tempo가 아니라 Connect REST task `trace` field를 노출하며(`get_connector_task_trace`로 분리 #368), Tempo 기반 교체는 #373 |

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

- **`PipelineStatusService.recompute`**: 현재 `PipelineStatusServiceImpl`은 connector state 집합과 source/sink DB reachability를 입력으로 상태를 계산한다. consumer lag/error rate는 현재 `recompute` 입력이 아니다. 우선순위는 connector `FAILED`/`PARTIALLY_FAILED`→`error`, `PAUSED`→`paused`, 기대 connector 수만큼 `RUNNING`→`active`, 그 외→`creating`; DB가 `UNREACHABLE`이면 `creating`이 아닌 pipeline은 `error`다.
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
- **레벨→인시던트 [현재]**: 일부 poller 신호는 `EventService.record(...)`만 하고, 일부는 `IncidentService.onThresholdViolation(...)`으로 인시던트를 자동 생성한다(정확한 연결/미연결 목록은 아래 §5 [현재]). 목표 규칙(부록 B.7) 완성이 구현 보강 대상이다.

### 5. 인시던트 엔진 (`incident`)

**[현재]** `IncidentService`는 `onThresholdViolation(...)`/`onRecovery(...)`를 제공하며, 인시던트 자동 생성은 이미 여러 poller 경로에 **연결되어 있다**: `PipelineStatusServiceImpl`(pipeline `error` 전이[connector FAILED·DB UNREACHABLE]·error rate `> 0.5%`/`> 2.0%`, `recordStatusEvent`/`recordErrorRateThresholdInput`), `KafkaAdminPoller`(topic ERROR/WARN, consumer lag **CRITICAL** `evaluateLag`), `ConnectRestPoller`(connector task FAILED — DB연결 실패는 `datasource` grouping으로 dedup). 반면 consumer lag **WARNING**과 DB replication lag(`DatabaseHealthProbeJob` → `DB_REPLICATION_LAG_WARNING`)은 아직 `EventService.record(...)`만 하고 인시던트로 승격하지 않는다. **목표 규칙(전체 신호 커버리지·그룹화)은 [부록 B.7](../../spec.md#b7-인시던트-자동-생성-및-그룹화-규칙)**, 현재 데이터 모델은 [data-model §3.7](./data-model.md#4-data-model)을 따른다.

**[현재]** 인시던트 생성·에스컬레이션 동작(코드 정본 `IncidentService.java`):

```text
IncidentService.onThresholdViolation(...):
  기존 활성(OPEN/INVESTIGATING) incident가 있으면:
     게이팅 없이 event를 attach하고, WARNING + ERROR event면 CRITICAL로 escalation
  없으면:
     ERROR event → 즉시 incident 생성(severity=CRITICAL)
     WARN event → 동일 grouping_key 30분 창에서 2건 도달 시에만 생성(severity=WARNING)
                  (단건은 event row만 기록, 인시던트 미생성)
  생성 시 SSE incident_opened, attach/escalation 시 incident_updated
```

- **그룹화 키**(B.7): 동일 Source DB 다수 Pipeline FAILED→`source_db_id` · 동일 Worker 다수 connector→`worker_id` · 동일 CG lag+REBALANCING→`consumer_group` · 연쇄 replication lag→pipeline lag→`source_db_id`. 코드의 `IncidentGroupingKeys`는 `pipelineAvailability`/`pipelineErrorRate`/`datasource`/`connectorWorker`/`consumerLag`/`topicReplication` 키를 만든다.
- **그룹 멤버십**: `events.incident_id` 컬럼은 존재하고 인시던트 생성·attach 시 `recordWithIncident(...)`로 연결된다. 다만 `incident`에는 `trigger_event_id` 컬럼이 없고 event timestamp는 `created_at`이다([data-model §3.7](./data-model.md#4-data-model)).
- **severity** **[현재]**: `IncidentService`는 WARN event→`WARNING`, ERROR event→`CRITICAL`을 저장하고, 기존 `WARNING` incident에 ERROR event가 붙으면 `CRITICAL`로 올린다(단일 level 축). Impact×Urgency·SLO burn-rate 기반 산정과 `severity_reason` 기록은 **[계획 §11]**(아래 §10).
- **복구 처리**: `IncidentService.onRecovery(...)`는 자동으로 `RESOLVED`로 닫지 않는다. `CRITICAL`은 사용자 확인 전까지 유지하고, `WARNING` + `OPEN`은 `INVESTIGATING`으로 전이해 복구 확인을 요청한다.
- **멱등 생성**: 같은 key의 활성(`OPEN`/`INVESTIGATING`) 인시던트가 있으면 새로 만들지 않고 attach하며, 경합으로 둘 이상이 열리면 첫 건만 남기고 나머지를 `RESOLVED`로 정리한다(`closeDuplicateActiveIncidents`). grouping은 `lockIncidentGroup`으로 직렬화한다.
- **RCA 기록(FR-026)**: 현재 controller-mapped RCA write route는 없다. `IncidentService.updateRca()` 내부 메서드는 `rca` field만 저장하며 severity 보정/report reference 기록은 하지 않는다. 상세 조회 시 AI 분석 리포트가 있으면 `backfillRcaIfMissing(...)`이 리포트 요약으로 `rca`를 채운다.

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
| FR-019 | Alerts event log | `/events` | `event` 테이블. Frontend는 AlertsView 통합 이벤트 로그에서 표시 |
| - | Backend aggregate(프론트 라우트 없음) | `/monitoring/overview` | 집계 query |
| FR-021 | Alerts | `/incidents`·`/incidents/{id}` | `IncidentService.list/get`의 incident row. `IncidentResponse`에는 event 역참조 목록이 없다 |
| FR-023 | Cluster | `/api/v1/clusters/kafka`·`/api/v1/clusters/connect` | KafkaAdmin broker + JMX worker. Broker 인프라 지표는 프론트에서 미노출 |
| FR-024 | Resource events | `/monitoring/resource-events` | 현재 `MonitoringReadService.resourceEvents(...)`는 `AdminClient.listPartitionReassignments()` 기반 `PARTITION_REASSIGNMENT`만 반환하며 AlertsView 통합 이벤트 로그에서 표시한다 |

### 9. 구현 메모

- **스케줄러·분산 락**: 다중 replica에서 폴링 중복을 막으려면 ShedLock 등으로 collector를 단일 실행. SSE fan-out·run 잠금이 필요하면 Redis(선택).
- **writer 경계**: `event`·`incident` 변경은 각 서비스로 직렬화한다. `pipeline.status`는 자동 전이는 `PipelineStatusService`, 사용자 pause/resume은 `PipelineService`가 직접 저장한다.
- **diff/중복 억제 상태**: 직전 스냅샷(메모리 캐시 또는 last_* 컬럼)으로 "전이/최초/복구"만 이벤트화.
- **retention**: `event`는 보존 정책으로 아카이브, 인시던트는 `RESOLVED` 후 일정 기간 유지.
- **테스트 기준**: (a) 임계 경계값에서 정확히 한 번 이벤트 생성, (b) 동일 트리거 반복 시 중복 인시던트 미생성(멱등), (c) WARNING→CRITICAL 에스컬레이션, (d) 복구 시 CRITICAL 수동 해소 권고/WARNING `INVESTIGATING` 전이, (e) EDA는 lag 인시던트 미생성, (f) SSE 전이 1:1.

### 10. 사용자 영향 SLI/SLO·burn-rate 알림 **[계획 §10·§11]**

> 이 절은 to-be 설계다. 현재 인시던트/알림은 §4·§5처럼 정적 임계값·ERROR/WARN·에스컬레이션 기반이며, 이 절의 SLI/SLO·burn-rate·severity 산정·라우팅은 아직 코드에 없다. 외부 기준은 [rca-standards-review.md §2.4·§Q4·§5.2~§5.4·§7(item10·11)](../rca-standards-review.md), 임계값 정본은 [spec.md 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)다. 임계값 수치는 여기서 재정의하지 않고 부록 B를 인용한다.

#### 10.1 동기 — 증상(사용자 영향) 우선 **[계획 §10]**

[현재] 알림은 대부분 connector FAILED, consumer lag, replication lag, error rate 같은 **원인 기반(cause-based) 정적 임계값**이다. SRE는 "what's broken(증상)"을 "why(원인)"보다 우선해 알림하라고 권고한다(rca-standards-review §Q4). 따라서 page는 원인 지표가 아니라 **사용자 영향 SLI의 SLO가 빠르게 깨질 때** 발생하도록 바꾸고, 원인 지표는 진단 근거(RCA evidence)로 재배치한다.

#### 10.2 사용자 영향 SLI 정의 **[계획 §10]**

`good_event / total_event`로 측정하는 사용자 관점 SLI를 상위 지표로 둔다. 각 SLI는 부록 B의 원인 지표를 근거(evidence) 신호로 매핑한다(값은 부록 B 인용).

| SLI | good event 정의 | bad event 예시 | 관련 원인 지표(부록 B) |
| --- | --- | --- | --- |
| 데이터 신선도 | source 이벤트가 `freshness_objective_minutes` 안에 sink에 정확히 1회 반영 | sink 반영 지연, consumer lag 장기 증가 | consumer lag(B.1 `≥ 5,000`/`≥ 50,000`), sink write latency |
| end-to-end latency | source timestamp → sink committed timestamp가 `latency_objective_ms` 이하 | 처리 지연, connector backpressure | connector task 상태(B.2), replication lag(B.2 `≥ 1,000ms`/`≥ 5,000ms`) |
| 처리 성공률 | 전체 이벤트 중 정상 처리·적재 성공 | connector error, DLQ 증가, retry exhaustion | error rate(B.1 `> 0.5%`/`> 2%`), connector FAILED |
| 데이터 완전성 | 기대 row/event 수와 sink 반영 수가 허용 오차 내 일치 | 누락, 중복, schema reject | schema mismatch, sink constraint error |
| provisioning 성공률 | pipeline 생성 요청이 허용 시간 내 `active`로 전환 | pipeline 생성 5분 초과(B.1), connector 생성 실패 | provisioning event, `pipeline.provisioning-timeout=PT5M` |

> SLI good/bad 정의는 문서화하되, SLO 목표 수치(`freshness_objective_minutes` 등)는 2~4주 baseline 데이터로 보정한 뒤 확정한다(rca-standards-review §5.3·§8). consumer lag 행은 부록 B와 동일하게 Bifrost가 컨슈머를 소유하는 **CDC(JDBC Sink) consumer group**에만 적용하고, EDA(fan-out)는 외부 구독자 lag으로 SLI 위반을 만들지 않는다.

#### 10.3 SLO burn-rate 알림 규칙 **[계획 §11]**

Google SRE multi-window multi-burn-rate를 **시작값**으로 둔다(Bifrost 데이터로 보정).

| 알림 클래스 | window(long/short) | burn-rate | error budget 소진 | 라우팅 |
| --- | --- | --- | --- | --- |
| page | 1h / 5m | `@14.4x` | 약 2% | page |
| page | 6h / 30m | `@6x` | 약 5% | page |
| ticket | 3d / 6h | `@1x` | 약 10% | ticket |

- short window는 long window의 약 1/12 기간으로 둔다(false alarm/reset time 억제).
- low-traffic pipeline은 precision/recall/detection time/reset time을 보고 별도 보정한다.
- `IncidentSeverity=CRITICAL`은 SLO burn-rate **page** 조건과, `WARNING`은 **ticket** 조건과 연결한다.

#### 10.4 severity = Impact × Urgency **[계획 §11]**

[현재] severity는 event level 단일 축(ERROR→CRITICAL, WARN→WARNING)이다. ITIL Priority = Impact × Urgency, PagerDuty severity 분류를 반영해 2축으로 보강한다.

- **Impact**: 영향 범위 — 몇 개 pipeline/workspace/sink가 영향받는가(`affected_resource_count`).
- **Urgency**: error budget 소진 속도(`slo_burn_rate`)와 복구 가능 시간.
- 산정 근거를 `severity_reason`에 남긴다: `{ impact, urgency, slo_burn_rate, affected_resource_count }`. 스키마는 [data-model §3.10.3](./data-model.md#4-data-model) **[계획 §11]**.

#### 10.5 정적 임계값 → page/ticket/diagnostic 재분류 **[계획 §11]**

기존 부록 B 정적 임계값은 버리지 않는다. 사용자 영향 SLO 위반 여부에 따라 라우팅을 분리한다(라우팅 정본 표는 rca-standards-review §5.4).

| 신호 | [현재] 처리 | [계획 §11] 처리 |
| --- | --- | --- |
| 사용자 영향 SLO burn-rate page 조건 충족 | 별도 SLO 기준 없음 | `CRITICAL` incident + **page** |
| 사용자 영향 SLO burn-rate ticket 조건 충족 | 별도 SLO 기준 없음 | `WARNING` incident + **ticket** |
| consumer lag WARN/CRIT(B.1 `≥ 5,000`/`≥ 50,000`) | 임계값 기반 처리(현재 lag `≥ 5,000`은 pipeline `lag` 전이, lag CRITICAL(`≥ 50,000`)은 `onThresholdViolation` 인시던트 생성) | SLO 영향 있으면 page/ticket, 없으면 **diagnostic_signal**(RCA evidence) |
| connector FAILED(B.2) | 원인 기반 incident(`error` 전이→`onThresholdViolation`) | 영향 SLI 악화 있으면 page/ticket, 없으면 ticket + RCA evidence |
| replication lag(B.2 `≥ 1,000ms`/`≥ 5,000ms`) | 임계값 기반 event | 데이터 신선도 SLI 영향 있으면 ticket/page, 단기 회복이면 **diagnostic_signal** |
| error rate(B.1 `> 0.5%`/`> 2%`) | `> 2%`에서 pipeline `error` + `onThresholdViolation` | 처리 성공률 SLO 영향으로 page/ticket 분류, 없으면 diagnostic |
| pipeline 생성 5분 초과(B.1) | 정적 임계값(`error` 전이) | provisioning SLO 위반으로 ticket/page |

- 알림 라우팅 값은 `page | ticket | diagnostic_signal` 3종이며, 원인 지표는 RCA evidence로 남고 page는 사용자 영향 SLO 위반에 집중한다.
- 정적 임계값 수치 자체는 [§8 pipeline.md](./pipeline.md#8-pipeline-domain)의 상태 머신과 동일하게 부록 B를 단일 출처로 인용한다. 임계값 이름·버전·근거·owner·보정 메타는 [governance §7](./governance.md#7-governance-engine) threshold registry **[계획 §3]**와 연계한다.

#### 10.6 테스트 기준 **[계획 §10·§11]**

(a) 정의된 SLI마다 `good_event/total_event`가 Prometheus/DB 쿼리로 산출 가능, (b) burn-rate page/ticket 규칙이 윈도우 경계에서 정확히 트리거, (c) SLO 영향 없는 정적 임계값은 page가 아니라 diagnostic_signal로 강등, (d) `severity_reason`에 impact·urgency·slo_burn_rate·affected_resource_count가 남음.
