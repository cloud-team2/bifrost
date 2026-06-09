# Spring Boot Operations Backend — Monitoring & Incident Engine

> 요약은 [overview.md](./overview.md). 이 파일은 **관측 데이터 수집 → 상태 산정 → 이벤트/인시던트 자동 생성 → SSE 발행**과 모니터링 read(Sync/Messages/Metrics) 구현을 다룬다. 상태값·임계값·이벤트→인시던트 규칙의 **정본은 [기능명세서 부록 B](../../spec.md#부록-b--리소스-상태값-정의-및-자동-기준-단일-출처)** 이며, 이 문서는 그 구현 설계다(중복 정의하지 않고 인용).
>
> 패키지: `monitoring`(query·collector) · `event` · `incident` ([server.md §5](./server.md#5-패키지-구조)). 모니터링 read는 플랫폼(`/api/v1`)과 agent(`/internal/ops`)가 `monitoring.query`(port)를 공유한다.

## 6. Monitoring and Incident Engine

### 1. 목적·범위

부록 B를 코드로 구현하는 계층. 두 흐름으로 나뉜다.

```text
[수집] Watcher(상태 event) + 폴링 수집기(지표) + 쿼리 어댑터(metric/log/trace)
   → [상태 산정] raw → pipeline.status / connector.state / db.health / cg.state (부록 B.1~B.4)
   → [이벤트 엔진] 임계 비교 → event row 생성 (부록 B.6)
   → [인시던트 엔진] event → 자동 생성·그룹화·severity·auto-resolve (부록 B.7)
   → [SSE] pipeline_status_changed · connector_state_changed · incident_opened/updated
[read]  Sync(FR-009) · Messages(FR-010) · DB Metrics(FR-017) 는 요청 시 query
```

커버 FR: 모니터링 read(FR-006~009·017·020·023), 이벤트/인시던트(FR-019·021·024·026 탐지측). **단일 writer 원칙**: `pipeline.status`·`event`·`incident` 쓰기는 각 도메인 서비스(`PipelineStatusService`/`EventService`/`IncidentService`) 한 곳을 통과한다(동시성·중복 방지).

### 2. 수집 계층 (collectors)

상태 전이는 Watcher(event-driven), 지표는 주기 폴링, metric/log/trace는 on-demand 질의로 모은다. 주기·소스의 정본은 부록 B.6 각 표(헤더에 명시)다.

| 수집기 | 방식·주기 | 소스 | 산출(요약) |
| --- | --- | --- | --- |
| **ConnectorWatcher** | watch(event) | Fabric8 `KafkaConnector .status` | connector/task state 전이 → `PipelineStatusService` ([provisioning §6](./provisioning.md#2-provisioning)) |
| **KafkaAdminPoller** | 30s | Kafka AdminClient | consumer group lag/offset/state(B.4), topic 메타·partition |
| **ConnectRestPoller** | 10s | Connect REST `GET /connectors/{n}/status` | connector·task state, 오류 이력(retry), config(redacted) |
| **JmxPoller** | 60s | Jolokia/JMX | worker JVM heap·cpu·gc(B.6.4), `connector-task-metrics`(poll batch·records/sec·error rate) |
| **DbHealthPoller** | ping 5s / lag 30s | source/sink DB(동적 DataSource) | 연결, PG `confirmed_flush_lsn` diff / MariaDB `Seconds_Behind_Master`, retainedWAL(B.3) |
| **쿼리 어댑터** | on-demand | Prometheus/Loki/Tempo HTTP | 차트·로그·trace(프론트 시각화·agent evidence) |

구현 메모:
- 폴링 수집기는 Spring `@Scheduled`(또는 ShedLock 분산 락) + project별 fan-out. 한 주기 실패가 다른 project를 막지 않도록 per-resource try/catch.
- 폴링 결과는 **이전 스냅샷과 비교(diff)** 해 전이가 있을 때만 이벤트·SSE를 낸다(no-op 폴링은 조용히 통과).
- Watcher가 상태 전이의 1차 소스, 폴링은 lag/지표처럼 watch로 안 잡히는 값과 watch 누락 보정(reconcile)을 담당한다.

### 3. 상태 산정 (status derivation)

raw 수집값 → 상태 enum 매핑. **정의는 부록 B, 이 절은 산정 위치만 고정**한다.

| 대상 | 결과 enum | 산정기 | 정본 |
| --- | --- | --- | --- |
| pipeline | `creating`/`active`/`lag`/`error`/`paused` | `PipelineStatusService.recompute(pipelineId)` | [B.1](../../spec.md#b1-pipeline-상태값) |
| connector | `RUNNING`/`PARTIALLY_FAILED`/`FAILED`/`PAUSED`/`UNASSIGNED` | Watcher+ConnectRestPoller | [B.2](../../spec.md#b2-connector-인스턴스-상태값) |
| database | `healthy`/`warning`/`error` (`health_status`) | `DbHealthPoller` → `database.health_status` | [B.3](../../spec.md#b3-databasenode-상태값) |
| consumer group | `STABLE`/`REBALANCING`/`DEAD`/`EMPTY` | `KafkaAdminPoller`(live, 미영속) | [B.4](../../spec.md#b4-consumer-group-상태값) |

- **`PipelineStatusService.recompute`**: connector state + consumer lag을 입력으로 B.1 규칙을 적용한다(예: 모든 task RUNNING & lag<5,000 → `active`; 일부 task FAILED → `PARTIALLY_FAILED`→pipeline `lag`; task FAILED 또는 error rate>2% → `error`). **EDA(fan_out)는 sink consumer가 없으므로 lag을 보지 않고 Source connector state로만 산정**한다([B.1 EDA 단서](../../spec.md#b1-pipeline-상태값)).
- **DB 상태의 파이프라인 전이(#179)**: `database`는 등록 1회가 아니라 `DatabaseHealthProbeJob`(60s)으로 주기 프로브하며, `UNREACHABLE`이 되면 `reevaluateForDatasource`로 해당 파이프라인을 `error`로 전이한다(원인 메시지 포함). connector가 RUNNING이어도 source/sink DB가 끊기면 파이프라인은 error다. 정본 [lifecycle.md §2·§5](./lifecycle.md).
- **lag/미동기화 메트릭은 consumer group으로 필터(#200)**: `kafka_consumergroup_lag`를 토픽으로 합산하면 같은 토픽을 구독하던 **삭제된 파이프라인의 orphan group**까지 더해진다. 해당 파이프라인 sink group(`connect-<pid>-sink`)으로만 필터해야 정확하다.
- **데이터 전송 시간(소스 지연)**: `millisecondsbehindsource`는 순간 게이지라 부하 타이밍에 따라 크게 튀므로 `avg_over_time`(≥60s)으로 평활화해 추세를 보인다(#200). 측정 불가(idle)는 `-1` → 프론트가 그래프 gap 처리.
- pipeline·connector·db는 metadb에 영속(전이 시 갱신), consumer group state는 live 조회(테이블 없음).

### 4. 이벤트 엔진 (`event`)

상태 전이·임계 초과를 `event` row로 적재한다. **카탈로그(트리거→레벨→인시던트 여부)의 정본은 [부록 B.6](../../spec.md#b6-이벤트-카탈로그)** 다.

흐름:

```text
collector/Watcher → 임계·전이 판정 → EventService.emit(category, level, refs, message)
  → event row insert (level INFO/WARN/ERROR, category, pipeline_id, occurred_at)
  → level≥WARN 이면 IncidentEngine.evaluate(event)
  → SSE(해당 SSE 채널)
```

규칙:
- **중복 억제**: 같은 리소스·같은 트리거의 동일 상태가 지속되면 매 폴링마다 이벤트를 만들지 않는다. "최초 진입"·"복구"만 이벤트화(예: lag≥5,000 *최초* WARN, lag<5,000 *복구* INFO — B.6.1).
- **category**: `pipeline`/`database`/`consumer_group`/`connect_worker`/`user_action`/`resource`([data-model §3.6](./data-model.md#4-data-model)). 사용자 액션(B.6.5)은 항상 INFO, 인시던트 없음.
- **레벨→인시던트**: INFO=로그만, WARN=조건부, ERROR=항상 → [§5](#5-인시던트-엔진-incident).

### 5. 인시던트 엔진 (`incident`)

`event` → 인시던트 자동 생성·그룹화·severity·auto-resolve. **규칙 정본은 [부록 B.7](../../spec.md#b7-인시던트-자동-생성-및-그룹화-규칙)**, 데이터 모델은 [data-model §3.7](./data-model.md#4-data-model).

```text
IncidentEngine.evaluate(event):
  level INFO            → return (로그만)
  level WARN, 단건      → return (동일 리소스 30분 내 2건 누적 시 생성)
  level WARN, 2건+/30m  → openOrAttach(WARNING)
  level ERROR           → openOrAttach(CRITICAL)   # 즉시

openOrAttach(severity):
  key = groupingKey(event)                         # B.7 그룹화 키
  inc = findOpenIncidentByKey(key)
  if inc == null: inc = create(severity, trigger_event_id=event.id, grouping_key=key)
  else:           escalateIfNeeded(inc, severity)  # WARNING+ERROR → CRITICAL
  event.incident_id = inc.id                        # 그룹 멤버십 단일 출처(역참조)
  affected_rows_estimate = estimate(inc)            # sync gap/consumer lag 추정(FR-021/026)
  SSE incident_opened|incident_updated
```

- **그룹화 키**(B.7): 동일 Source DB 다수 Pipeline FAILED→`source_db_id` · 동일 Worker 다수 connector→`worker_id` · 동일 CG lag+REBALANCING→`consumer_group` · 연쇄 replication lag→pipeline lag→`source_db_id`.
- **그룹 멤버십**은 `event.incident_id` 역참조 단일 출처(별도 배열 금지), 타임라인은 `event.occurred_at` 정렬·`trigger_event_id` 강조([data-model §3.7](./data-model.md#4-data-model)).
- **severity**: 관련 이벤트에 ERROR 포함→CRITICAL, 전부 WARN→WARNING, WARNING에 ERROR 추가→CRITICAL 에스컬레이션(낮추지 않음).
- **auto-resolve**: 트리거 복구 시 권고 메시지(사용자가 `resolved` 확정) · ERROR 인시던트는 자동 닫기 없음 · WARNING+복구 이벤트는 자동 `investigating`+알림.
- **멱등 생성**: 같은 key의 open 인시던트가 있으면 새로 만들지 않고 attach. 동시 폴링 경쟁은 `IncidentService` 단일 writer + `unique(workspace_id, grouping_key) where status<>resolved` 부분 유니크로 보호.
- **RCA 기록(FR-026)**: 인시던트는 자동 생성되나 RCA 분석 run은 사용자가 시작하고, Verifier 통과분만 [`PATCH /internal/ops/.../incidents/{id}/rca`](../../api/springboot.md#24-report-support-api)로 `root_cause_summary`·severity 보정이 기록된다(agent 소관).

### 6. 파생 read — Sync / Messages / DB Metrics

요청 시 계산하는 조회(폴링 영속 아님).

- **Sync (FR-009)**: source→sink 동기화율을 **Kafka 오프셋 기반 근사**로 산출 — `(sink JDBC connector 소비 오프셋) / (source 토픽 produce 오프셋)`. 고객 DB `count(*)` 정밀 비교는 v1 미사용(부하 회피). 지연 ms는 토픽 record timestamp vs sink 커밋 시각.
- **Messages (FR-010)**: 토픽 **최근 N건 bounded consume**(`auto.offset.reset` 영향 없는 일회성 consumer로 end-N..end 범위 read) → Debezium before/after 표시. Topic 이름은 alias로만 노출([FR-010](../../spec.md#fr-010--토픽-메시지-조회)).
- **DB Metrics (FR-017)**: `database.inspector`가 엔진별 stat 질의 — PG `pg_stat_activity`(활성 연결)·`pg_stat_database`(tps 근사), MariaDB `SHOW GLOBAL STATUS`(`Threads_connected`·`Questions`). v1 기본: 활성 연결·기본 TPS([database-registry](./database-registry.md#3-database-registry)).

### 7. SSE 발행 (`streaming`)

플랫폼 SSE(workspace 범위·장수명)로 상태 변화를 push한다(Agent run SSE와 별도 — [frontend](../frontend.md)).

| 이벤트 | 트리거 | 소비 |
| --- | --- | --- |
| `pipeline_status_changed` | `PipelineStatusService.recompute` 전이 | PipelinesView·상세 |
| `connector_state_changed` | Watcher/ConnectRestPoller 전이 | 상세 Connector 탭(토글) |
| `incident_opened` / `incident_updated` | `IncidentService` 생성·갱신 | 사이드바 배지·AlertsView |

엔드포인트 `GET /api/v1/workspaces/{wsId}/events/stream`([Spring Boot API: Workspace Event Stream](../../api/springboot.md#workspace-event-stream)). `EventSource`는 헤더 불가 → JWT `?access_token=` 쿼리.

### 8. 플랫폼 read API 매핑

| FR | 화면 | API([Spring Boot API Reference](../../api/springboot.md)) | 데이터 출처 |
| --- | --- | --- | --- |
| FR-006 | Overview | `/pipelines/{id}/metrics` | KafkaAdmin lag + JMX rate/error |
| FR-007 | Consumers | `/pipelines/{id}/consumer-groups` | KafkaAdmin(B.4) |
| FR-008 | Connector | `/pipelines/{id}/connectors` | Watcher+ConnectRest+JMX |
| FR-009 | Sync | `/pipelines/{id}/sync` | §6 오프셋 근사 |
| FR-010 | Messages | `/pipelines/{id}/messages` | §6 bounded consume |
| FR-017 | DB Metrics | `/databases/{id}/metrics` | §6 inspector |
| FR-019 | Activity | `/events` | `event` 테이블 |
| FR-020 | Overview(운영) | `/overview` | 집계 query |
| FR-021 | Alerts | `/incidents`·`/incidents/{id}` | `incident`+역참조 `event` |
| FR-023 | Cluster | `/cluster` | KafkaAdmin broker + JMX worker |
| FR-024 | Resource events | `/resource-events` | KafkaAdmin(리밸런스·리더선출) |

### 9. 구현 메모

- **스케줄러·분산 락**: 다중 replica에서 폴링 중복을 막으려면 ShedLock 등으로 collector를 단일 실행. SSE fan-out·run 잠금이 필요하면 Redis(선택).
- **단일 writer**: `pipeline.status`·`event`·`incident` 변경은 각 서비스 한 곳으로 직렬화(Watcher/폴링/agent 조치가 동시에 들어와도 일관).
- **diff/중복 억제 상태**: 직전 스냅샷(메모리 캐시 또는 last_* 컬럼)으로 "전이/최초/복구"만 이벤트화.
- **retention**: `event`는 보존 정책으로 아카이브, 인시던트는 resolved 후 일정 기간 유지.
- **테스트 기준**: (a) 임계 경계값에서 정확히 한 번 이벤트 생성, (b) 동일 트리거 반복 시 중복 인시던트 미생성(멱등), (c) WARNING→ERROR 에스컬레이션, (d) 복구 시 auto-resolve 권고, (e) EDA는 lag 인시던트 미생성, (f) SSE 전이 1:1.
