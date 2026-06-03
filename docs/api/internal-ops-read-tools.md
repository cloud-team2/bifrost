# Agent Read Tool 계약 — `/internal/ops` (DB·Pipeline)

> FastAPI Agent가 호출하는 Spring `/internal/ops` **read-only** tool의 입력·출력·error mapping·evidence 반환 규칙을 한곳에 모은 계약 문서(#31). 엔드포인트 표면·공통 헤더·응답 봉투의 **정본(SoT)은 [api/springboot.md Part B](./springboot.md)**이며, 이 문서는 FastAPI tool-catalog의 논리 tool과 그 엔드포인트를 잇는 **매핑 계약**이다. tool 이름·risk는 [tool-catalog.md §10](../design/backend-fastapi/tool-catalog.md)를 따른다.
>
> 신뢰 경계: FastAPI는 운영 리소스를 직접 만지지 않고 모든 조회를 이 API로 위임한다([server.md §3](../design/backend-springboot/server.md), [agent-principles](../design/backend-fastapi/agent-principles.md)). 이 문서의 tool은 전부 `read_only`라 정책상 자동 허용(mutation·승인은 범위 밖).

## 1. 공통 규칙 (SoT = Part B §2)

| 항목 | 규칙 |
| --- | --- |
| Canonical prefix | `/internal/ops/projects/{project_id}` (`project_id` = workspace_id = tenant_id) |
| 인증 | 사용자 JWT가 아니라 **FastAPI service identity**(service token). 사용자 권한은 전달값을 믿지 않고 Spring이 재확인 ([auth.md §4](../design/backend-springboot/auth.md)) |
| 필수 헤더 | `X-Agent-Run-Id`, `X-Agent-Step-Id`, `X-Agent-Name`, `X-Actor-Type: agent`, `X-Actor-Id` |
| idempotency | read tool은 **불필요**(`X-Idempotency-Key`는 mutation 전용) |
| 성공 봉투 | `{ ok:true, request_id, data, evidence? }` (Part B §2) |
| 실패 봉투 | `{ ok:false, request_id, error:{ code, message } }` — `code`는 [error-codes.md](./error-codes.md) 표준 코드 |
| evidence | **raw content를 State/응답에 inline 금지.** 조회 결과의 원문(로그·메트릭 등)은 Evidence Store에 적재하고 `evidence[]`에 `evidence_id`·`store_ref`·`summary`만 반환 (Part B §2, agent-principles evidence-first) |

## 2. Ready dependency

각 read tool은 백엔드 하위 시스템에 의존한다. Agent는 호출 전 [`GET /internal/ops/ready`](./springboot.md#part-b--내부-운영-api-internalops-agent-facing)로 준비 상태를 확인하고, 미준비 의존은 `UNKNOWN_WITH_EVIDENCE_GAP`로 처리한다([agent-principles](../design/backend-fastapi/agent-principles.md)).

| Tool | 의존 하위 시스템 |
| --- | --- |
| `list_project_pipelines` · `get_pipeline_topology` | metadb(메타데이터) |
| `get_connector_status` | Kafka Connect REST |
| `get_consumer_lag` | Kafka Admin (consumer offset) |
| `search_logs` | Log Store(Loki 등) + Evidence Store |
| `get_incident_summary` | metadb(incident·event) |

## 3. Read tool ↔ 엔드포인트 매핑 계약

각 tool의 출력은 §1 성공 봉투의 `data`다. `project_id`는 모든 입력에 공통이라 표의 입력에서는 생략한다.

### 3.1 `list_project_pipelines`

| | |
| --- | --- |
| 엔드포인트 | `GET /internal/ops/projects/{project_id}/pipelines` |
| 입력 | (없음) — 선택 필터 `status?` |
| 출력 `data` | `pipelines[]`: `{ pipeline_id, name, pattern(fan_out/direct), source_db_id, sink_db_id?, status, updated_at }` |
| evidence | 불필요(메타데이터) |

### 3.2 `get_pipeline_topology`

| | |
| --- | --- |
| 엔드포인트 | `GET /internal/ops/projects/{project_id}/pipelines/{pipeline_id}` |
| 입력 | `pipeline_id` |
| 출력 `data` | `{ pipeline_id, pattern, source{db_id,alias}, sink?{db_id,alias}, connectors[]{cr_name,kind,state}, topics[], status }` |
| evidence | 불필요 |
| 오류 | pipeline 없음 → `PIPELINE_NOT_FOUND`(404), project 소유 아님 → `RESOURCE_NOT_OWNED_BY_PROJECT`(403) |

### 3.3 `get_connector_status`

| | |
| --- | --- |
| 엔드포인트 | `GET /internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status` |
| 입력 | `connector_name` |
| 출력 `data` | `{ connector_name, state(RUNNING/PARTIALLY_FAILED/FAILED/PAUSED/UNASSIGNED), tasks[]{task_id,state,worker_id}, last_error?, observed_at }` |
| evidence | task failure trace는 별도 tool(`get_connector_task_trace`)이 evidence로 반환. 본 tool은 상태 요약 |
| 오류 | connector 없음 → `CONNECTOR_NOT_FOUND`(404), Connect 미도달 → `UPSTREAM_UNAVAILABLE`(503) |

### 3.4 `get_consumer_lag`

| | |
| --- | --- |
| 엔드포인트 | `GET /internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag` |
| 입력 | `consumer_group` |
| 출력 `data` | `{ consumer_group, total_lag, partitions[]{topic,partition,current_offset,log_end_offset,lag}, observed_at }` |
| evidence | lag 스냅샷을 evidence로 첨부 가능(`type: metric`) |
| 오류 | group 없음 → `CONSUMER_GROUP_NOT_FOUND`(404), Kafka 미도달 → `UPSTREAM_UNAVAILABLE`(503) |
| 비고 | 표준 이름은 `get_consumer_lag`. `get_kafka_lag`는 legacy alias([tool-catalog §10](../design/backend-fastapi/tool-catalog.md)) |

### 3.5 `search_logs`

| | |
| --- | --- |
| 엔드포인트 | `POST /internal/ops/projects/{project_id}/observability/logs/search` |
| 입력(body) | `{ query, time_range{from,to}, pipeline_id?, limit? }` |
| 출력 `data` | `{ match_count, summary }` — **원문 라인은 inline 금지** |
| evidence | **필수.** 매칭 로그 원문을 Evidence Store에 적재, `evidence[]`에 `{evidence_id, store_ref, type:"log", summary, redaction_status}` 반환. 수집 단계 redaction 적용 |
| 오류 | Log Store 미도달 → `UPSTREAM_UNAVAILABLE`(503) |

### 3.6 `get_incident_summary`

| | |
| --- | --- |
| 엔드포인트 | `GET /internal/ops/projects/{project_id}/incidents/{incident_id}/summary` |
| 입력 | `incident_id` |
| 출력 `data` | `{ incident_id, severity, status, trigger_event{event_id,level,occurred_at}, related_event_count, grouping_key, affected_rows_estimate?, root_cause_summary? }` |
| 관련 | 관련 리소스는 `.../incidents/{incident_id}/related-resources`(별도 read) |
| evidence | 불필요(메타데이터). RCA **기록**은 read 아님 → `PATCH .../incidents/{incident_id}/rca`(범위 밖) |
| 오류 | incident 없음 → `INCIDENT_NOT_FOUND`(404) |

## 4. Error mapping

실패는 §1 실패 봉투로 반환하고 `error.code`는 [error-codes.md](./error-codes.md) 표준 코드를 쓴다. read tool 공통 매핑:

| 상황 | HTTP | code | Agent 처리 |
| --- | --- | --- | --- |
| 인증(service token) 실패 | 401 | `UNAUTHENTICATED` | 호출 중단·운영 알림 |
| project 소유/scope 위반 | 403 | `RESOURCE_NOT_OWNED_BY_PROJECT` | 대상 재확인 |
| 리소스 없음 | 404 | `*_NOT_FOUND`(pipeline/connector/consumer_group/incident) | evidence gap 처리 |
| 입력 검증 실패 | 400 | `VALIDATION_FAILED` | parameter 재생성 |
| 하위 시스템 미도달(Kafka/Connect/LogStore) | 503 | `UPSTREAM_UNAVAILABLE` | retry policy → 실패 시 `UNKNOWN_WITH_EVIDENCE_GAP` |
| 내부 오류 | 500 | `INTERNAL_ERROR` | retry 후 중단 |

> `PIPELINE_NOT_FOUND`(40000~), `CONNECTOR_NOT_FOUND`, `CONSUMER_GROUP_NOT_FOUND`, `INCIDENT_NOT_FOUND`, `UPSTREAM_UNAVAILABLE`는 신규 코드가 필요하면 [error-codes.md](./error-codes.md)의 파이프라인/커넥터(40000~40999)·서버/인프라(50000~50999) 범위에서 부여한다(구현 시 확정).

## 5. FastAPI 공유 체크리스트 (#31 완료 조건)

- [x] DB/pipeline read tool `/internal/ops` 입출력 정의 (§3)
- [x] error mapping (표준 error code) (§4)
- [x] service token / 공통 header 규칙 (§1)
- [x] evidence reference 반환 규칙 (§1·§3.5) — raw content 대신 reference
- [x] ready dependency 정리 (§2)
- [ ] FastAPI(김연수)와 계약 공유·합의 — 본 문서 리뷰로 진행
