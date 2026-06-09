# Spring Boot Operations Backend — Pipeline Domain (생성·생명주기·상태 머신)

> 요약은 [overview.md](./overview.md). 이 파일은 `pipeline` **도메인**(검증·생성 오케스트레이션·생명주기·**상태 머신**)을 다룬다. 실제 Kafka CR 생성은 [provisioning.md](./provisioning.md), lag/지표 산정은 [monitoring.md](./monitoring.md), 상태값 정의는 [부록 B.1](../../spec.md#b1-pipeline-상태값). `pipeline`은 이들을 **오케스트레이션**할 뿐 CR/지표를 직접 만들지 않는다.
>
> **라이프사이클·실패 attribution·삭제 정책의 정본은 [lifecycle.md](./lifecycle.md)**(DB 헬스→파이프라인 전파 #179, 삭제 시 토픽·consumer group 정리 #200, creating 타임아웃 등). 이 문서의 상태 머신·생명주기 표는 요약이며 상세·근거는 lifecycle.md를 따른다.

## 8. Pipeline Domain

### 1. 목적·범위

파이프라인의 **도메인/비즈니스 계층**(FR-003~005). pipeline 테이블·REST API·검증·생명주기·상태 전이를 소유하고, 인프라(CR 생성·watch)는 `provisioning`(port)에, lag 산정은 `monitoring.query`에 위임한다. **상태 쓰기의 단일 출처는 `PipelineStatusService`**(§5).

### 2. 생성 마법사 처리 (FR-004)

`POST /api/v1/workspaces/{wsId}/pipelines {name, pattern, sourceDbId, sinkDbId?, schema, table}`.

**검증(순서대로, 실패 시 `VALIDATION_FAILED`)**

| 검증 | 규칙 |
| --- | --- |
| pattern | `fan_out`(EDA)→`sinkDbId` 없음 / `direct`(CDC)→`sinkDbId` 필수 |
| ownership | `sourceDbId`·`sinkDbId`가 해당 workspace 소유 |
| CDC 준비도 | source DB `cdc_overall_status` ≠ `BLOCKED` ([database-registry.md](./database-registry.md#3-database-registry)) |
| 단일 테이블 | `schema`·`table` 단일 지정 |
| 중복 이름 | `unique(workspace_id, name)` ([data-model §4 운영규칙](./data-model.md#4-data-model)) |

**시퀀스**

```text
validate
  → metadb pipeline insert (status = creating)            # 단일 writer
  → provisioning.createPipelineResources(command)          # KafkaConnector CR apply (port)
  → 응답 {pipeline_id, status: "creating"}                  # 즉시 반환(비동기 전이)
  → (Watcher) connector RUNNING 감지 → PipelineStatusService.recompute → active + SSE
  → (30초 내 미전이) creating 유지 + WARN 이벤트 (부록 B.6.1)
```

생성 응답은 `creating`이고, `active` 전이는 Watcher가 비동기로 만든다(프론트는 SSE 수신).

### 3. 상태 머신 (state machine)

상태값은 [부록 B.1](../../spec.md#b1-pipeline-상태값) 정본. 전이는 **`PipelineStatusService.recompute`만** 일으킨다.

| from → to | 트리거 | 입력원 |
| --- | --- | --- |
| (없음) → `creating` | 생성 요청 | pipeline.service |
| `creating` → `active` | 모든 connector RUNNING + lag<5,000 | ConnectorWatcher |
| `creating` → `error` | 생성 부분 실패 / connector FAILED | provisioning result · Watcher |
| `active` ↔ `lag` | consumer lag ≥/< 5,000, 또는 일부 task FAILED(`PARTIALLY_FAILED`) | monitoring(poll) · Watcher |
| `active`/`lag` → `error` | connector FAILED 또는 error rate>2% | Watcher · monitoring |
| `*` → `paused` | 사용자 pause | pipeline.service |
| `paused` → `active` | 사용자 resume | pipeline.service |
| `*` → (삭제) | 사용자 delete | pipeline.service |

- **EDA(fan_out)**: sink consumer가 없으므로 lag을 보지 않고 **Source connector state로만** 산정(`creating`/`active`/`error`/`paused`) — [B.1 EDA 단서](../../spec.md#b1-pipeline-상태값).
- `creating`은 RUNNING 전이까지(최대 30초) 유지, 초과 시 WARN(상태는 유지).

### 4. 생명주기 (FR-005)

| 동작 | 처리 |
| --- | --- |
| pause | `provisioning` KafkaConnector `state: paused` patch → status `paused` + 이벤트(INFO) |
| resume | `state: running` patch → 재계산(`active`) |
| delete | `provisioning.deletePipelineResources`(Source[+Sink] CR 삭제) → pipeline 행 제거 + 이벤트 |

- `creating` 상태에서는 pause/delete 비활성(프론트·서버 양쪽 가드).
- 모든 동작은 `audit_event` + `event`(B.6.5 사용자 액션, INFO) 기록.

### 5. PipelineStatusService — 단일 writer

`pipeline.status` 변경은 **이 서비스 한 곳**으로만 일어난다(Watcher·폴링·사용자 조치가 동시에 들어와도 일관).

```text
PipelineStatusService.recompute(pipelineId):
  connectorStates = connectorRepo.statesOf(pipelineId)      # Watcher 갱신분
  lag             = monitoring.query.consumerLag(pipelineId) # CDC만, EDA는 skip
  newStatus       = applyB1Rules(pattern, connectorStates, lag, errorRate)
  if newStatus != current:
     update status; emit event(전이); audit; SSE pipeline_status_changed
```

- 입력원: **ConnectorWatcher**(connector/task state, [provisioning §6](./provisioning.md#2-provisioning))·**monitoring.collector**(lag·error rate)·**사용자 조치**.
- 동시성: pipeline 행 단위 락 또는 버전 컬럼으로 recompute 직렬화.

### 6. SSE 배선

전이 시 `streaming`이 `pipeline_status_changed`를 push(상세 토글은 `connector_state_changed`). 채널·인증은 [monitoring.md §7](./monitoring.md#6-monitoring-and-incident-engine)·[Spring Boot API: Workspace Event Stream](../../api/springboot.md#workspace-event-stream).

### 7. 데이터·API

- 테이블 `pipeline`·`connector` — [data-model §3.4·§3.5](./data-model.md#4-data-model).
- API: `GET/POST .../pipelines`·`{id}/pause|resume`·`DELETE`는 [Spring Boot API Controller Coverage](../../api/springboot.md#controller-coverage)의 `PipelineController` family를 따른다. 상세 탭 read 중 metrics/consumer-groups/connectors/sync/messages는 `monitoring.query` 위임, **connection-guide(FR-011: topic alias·bootstrap·group·언어별 스니펫)·table-mapping(FR-012: 컬럼→Kafka 필드)** 은 pipeline/connector 메타데이터·schema에서 직접 구성.

### 8. 구현 메모

- **부분 실패**: provisioning result로 Secret/Topic/Connector 단계별 실패를 구분해 `error`로 반영(어디서 실패했는지 last_error).
- **Watcher 재구독**: `onClose` 시 재구독, 누락 보정은 폴링 reconcile([monitoring §2](./monitoring.md#6-monitoring-and-incident-engine)).
- **idempotent 생성**: 같은 (workspace,name) 재요청은 중복 이름 검증으로 차단. 생성 중 재요청은 기존 `creating` 반환.
- 테스트: EDA/CDC 생성→`creating`→`active`, 부분 실패→`error`, pause/resume/delete 전이, 단일 writer 보장, creating 중 pause/delete 차단.
