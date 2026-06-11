# Spring Boot Operations Backend — Pipeline Domain (생성·생명주기·상태 머신)

> 요약은 [overview.md](./overview.md). 이 파일은 `pipeline` **도메인**(검증·생성 오케스트레이션·생명주기·**상태 머신**)을 다룬다. 실제 Kafka CR 생성은 [provisioning.md](./provisioning.md), lag/지표 산정은 [monitoring.md](./monitoring.md), 상태값 정의는 [부록 B.1](../../spec.md#b1-pipeline-상태값). `pipeline`은 이들을 **오케스트레이션**할 뿐 CR/지표를 직접 만들지 않는다.
>
> **라이프사이클·실패 attribution·삭제 정책의 정본은 [lifecycle.md](./lifecycle.md)**(DB 헬스→파이프라인 전파 #179, 삭제 시 토픽·consumer group 정리 #200, creating 타임아웃 등). 이 문서의 상태 머신·생명주기 표는 요약이며 상세·근거는 lifecycle.md를 따른다.

## 8. Pipeline Domain

### 1. 목적·범위

파이프라인의 **도메인/비즈니스 계층**(FR-003~005). pipeline 테이블·REST API·검증·생명주기·상태 전이를 소유하고, 인프라(CR 생성·watch)는 `provisioning`(port)에 둔다. 현재 자동 상태 재계산은 `PipelineStatusServiceImpl`, 사용자 pause/resume은 `PipelineService`가 직접 status를 저장한다.

### 2. 생성 마법사 처리 (FR-004)

`POST /api/v1/workspaces/{wsId}/pipelines {name, pattern, sourceDbId, sinkDbId?, schema, table}`.

**검증(순서대로, 실패 시 `VALIDATION_FAILED`)**

| 검증 | 규칙 |
| --- | --- |
| pattern | `fan-out`(EDA)→`sinkDbId` 없음 / `direct`(CDC)→`sinkDbId` 필수 |
| ownership | `sourceDbId`·`sinkDbId`가 해당 workspace 소유 |
| CDC 준비도 | source DB `cdc_readiness_status` ≠ `BLOCKED` ([database-registry.md](./database-registry.md#3-database-registry)) |
| 단일 테이블 | `schema`·`table` 단일 지정 |
| 중복 이름 | `unique(workspace_id, name)` ([data-model §4 운영규칙](./data-model.md#4-data-model)) |

**시퀀스**

```text
validate
  → metadb pipeline insert (status = creating)
  → provisioning.createPipelineResources(command)          # KafkaConnector CR apply (port)
  → 응답 {pipeline_id, status: "creating"}                  # 즉시 반환(비동기 전이)
  → (Watcher) connector RUNNING 감지 → PipelineStatusService.recompute → active + SSE
  → (기본 5분 timeout) PipelineStatusServiceImpl.failTimedOutCreating → error
```

생성 응답은 `creating`이고, `active` 전이는 Watcher가 비동기로 만든다(프론트는 SSE 수신). timeout sweep은 기본 60초 주기로 실행된다.

### 3. 상태 머신 (state machine)

상태값은 [부록 B.1](../../spec.md#b1-pipeline-상태값) 정본. 자동 전이는 `PipelineStatusServiceImpl.recompute`/timeout 경로가 처리하고, 사용자 pause/resume은 현재 `PipelineService`가 직접 저장한다.

| from → to | 트리거 | 입력원 |
| --- | --- | --- |
| (없음) → `creating` | 생성 요청 | pipeline.service |
| `creating` → `active` | 기대 connector 수만큼 모두 RUNNING | ConnectorWatcher |
| `creating` → `error` | 생성 부분 실패 / connector FAILED | provisioning result · Watcher |
| `active` ↔ `lag` | RUNNING 상태에서 consumer group lag ≥ 5,000 ↔ < 5,000 (스펙 B.1) | KafkaAdminPoller → PipelineStatusService |
| `active`/`lag` → `error` | connector FAILED 또는 일부 task FAILED(`PARTIALLY_FAILED`, 스펙 B.4) 또는 source/sink DB `UNREACHABLE` | Watcher · DatabaseHealthProbeJob |
| `*` → `paused` | 사용자 pause | pipeline.service |
| `paused` → `active` | 사용자 resume | pipeline.service |
| `*` → (삭제) | 사용자 delete | pipeline.service |

- **EDA(fan-out)**: expected connector 수가 1개이므로 Source connector state로 산정한다.
- `creating`이 timeout을 넘기면 `PipelineStatusServiceImpl.failTimedOutCreating(...)`이 `error`로 전이한다.

### 4. 생명주기 (FR-005)

| 동작 | 처리 |
| --- | --- |
| pause | 현재 `PipelineService.pause(...)`는 metadb pipeline status만 `paused`로 바꾸고 사용자 이벤트를 기록한다. KafkaConnector state patch는 아직 연결되어 있지 않다. |
| resume | 현재 `PipelineService.resume(...)`는 metadb pipeline status를 `active`로 바꾸고 사용자 이벤트를 기록한다. |
| delete | `provisioning.deletePipelineResources`(Source[+Sink] CR 삭제) → pipeline 행 제거 + 이벤트 |

- `creating` 상태에서는 pause/delete 비활성(프론트·서버 양쪽 가드).
- 모든 동작은 `audit_event` + `event`(B.6.5 사용자 액션, INFO) 기록.

### 5. PipelineStatusService — 자동 전이 writer

자동 전이(Watcher·DB health·timeout)는 `PipelineStatusService`가 처리한다. 현재 사용자 pause/resume은 `PipelineService`가 status를 직접 저장하므로 단일 writer가 아니다.

```text
PipelineStatusService.recompute(pipelineId):
  connectorStates = connectorRepo.statesOf(pipelineId)      # Watcher 갱신분
  dbReason        = dbUnreachableReason(pipeline)            # creating은 제외
  newStatus       = dbReason ? error : computeStatus(pattern, connectorStates)
  if newStatus != current:
     update status; emit event(전이); audit; SSE pipeline_status_changed
```

- 입력원: **ConnectorWatcher**(connector/task state, [provisioning §6](./provisioning.md#2-provisioning))·DB reachability(`connection_status`)·**사용자 조치**·creating timeout.
- 동시성: pipeline 행 단위 락 또는 버전 컬럼으로 recompute 직렬화.

### 6. SSE 배선

전이 시 `streaming`이 `pipeline_status_changed`를 push(상세 토글은 `connector_state_changed`). 채널·인증은 [monitoring.md §7](./monitoring.md#6-monitoring-and-incident-engine)·[Spring Boot API: Workspace Event Stream](../../api/springboot.md#workspace-event-stream).

### 7. 데이터·API

- 테이블 `pipeline`·`connector` — [data-model §3.4·§3.5](./data-model.md#4-data-model).
- API: `GET/POST .../pipelines`·`{id}/pause|resume`·`DELETE`는 [Spring Boot API Controller Coverage](../../api/springboot.md#controller-coverage)의 `PipelineController` family를 따른다.
- 상세 탭 read는 현재 `PipelineController`가 pipeline-domain service(`PipelineSyncService`/`PipelineTopicService`/`PipelineMessageService`)를 직접 호출한다.
- **EDA Sync 탭**: `GET /{id}/sync-status`는 EDA(`FAN_OUT`) 파이프라인에서 `{"applicable": false, ...}` 를 반환한다(sink가 없으므로 동기화 지표 없음, #359). CDC(`DIRECT`)만 실제 row 수·delta를 반환한다.
- **EDA 지표**: EDA 파이프라인의 핵심 지표는 `GET /{id}/metrics/source-delay`(Debezium `MilliSecondsBehindSource` 시계열). 프론트 Topic 탭에 Source Delay 차트로 표시된다. CDC의 consumer lag(`/{id}/metrics/unsynced`)은 EDA에 적용되지 않는다.
- `GET /api/v1/workspaces/{wsId}/pipelines/{id}/connection-guide`와 `GET /api/v1/workspaces/{wsId}/pipelines/{id}/table-mapping`은 현재 `PipelineController`에 구현되어 있다.

Connection Guide 응답:

| Field | 설명 |
| --- | --- |
| `pipelineId`, `pipelineName` | pipeline 식별자와 표시명 |
| `bootstrapServers` | Kafka bootstrap |
| `recommendedGroupId` | `bifrost.{workspace.namespace}.{pipelineId}` |
| `authenticationMethod` | 현재 credential metadata 기준 |
| `credentialReference` | `namespace`, `secretName`, `keyRefs`, `availableKeys` |
| `authenticationTemplates` | SCRAM-SHA-512 또는 mTLS일 때 security protocol/properties template |
| `topics` | `{name, sourceTable, role}` 목록 |

Secret 원문은 반환하지 않는다. credential은 namespace/name/key reference와 available key 목록으로만 노출한다.

Table Mapping 응답:

| Field | 설명 |
| --- | --- |
| `pipelineId` | pipeline id |
| `sourceConnector` | source KafkaConnector name |
| `sinkConnector` | sink KafkaConnector name |
| `mappings` | `{sourceTable,kafkaTopic,sinkTable}` 목록 |

Mapping은 KafkaConnector config의 `table.include.list`, `topic.prefix`, sink `topics`, `table.name.format`, route transform에서 산출한다. config나 table 정보가 없으면 오류가 아니라 빈 mapping을 반환한다.

### 8. 구현 메모

- **부분 실패**: provisioning result로 `SECRET`/`SOURCE_CONNECTOR`/`SINK_CONNECTOR` 단계 실패를 구분해 `error`로 반영한다. 별도 Topic stage는 없다.
- **Watcher 재구독**: `onClose` 시 재구독한다. 현재 poller는 Connect REST task event만 기록하고 connector state/table 또는 pipeline status 누락 보정 reconcile은 수행하지 않는다.
- **중복 생성**: 같은 `(workspace,name)` 또는 같은 source·schema·table·pattern 재요청은 생성 상태와 무관하게 `VALIDATION_FAILED`로 차단한다.
- 테스트: EDA/CDC 생성→`creating`→`active`, 부분 실패→`error`, pause/resume/delete 전이, creating 중 pause/delete 차단.
