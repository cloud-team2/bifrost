# FastAPI Agent Server — Tool Catalog & MCP Decision

> 요약은 [overview.md](./overview.md). 이 파일은 Agent tool catalog/매핑(§4)과 MCP 결정(§5)을 담는다.

## 4. Tool Catalog


### 1. 목적

이 문서는 Agent가 사용할 수 있는 논리 tool, workflow action, Spring Boot Operations API 매핑을 정의한다.

핵심 원칙은 다음과 같다.

1. LLM은 API path를 직접 만들지 않는다.
2. Agent는 논리 tool 이름과 action type만 사용한다.
3. FastAPI Tool Client Registry가 논리 tool을 Spring Boot operation으로 매핑한다.
4. Spring Boot가 현재 mutation의 approval과 idempotency를 검증한다. audit/evidence reference 연결은 아직 구현되어 있지 않다.
5. Tool output은 Spring response를 `ToolResult`로 감싼다. 현재 mutation `OpsEnvelope.evidence`는 빈 배열이고 `audit_event_id` field는 생략된다.

API endpoint 상세는 Spring Boot API를 기준으로 한다. 이 문서는 섹션 번호 대신 API 영역명으로 참조한다.

### 2. Tool과 Action의 차이

Runbook의 `Action`이 항상 실행 tool은 아니다.

| 구분 | 의미 | 예시 |
| --- | --- | --- |
| `runtime_tool` | Spring Boot Operations API로 실행되는 단일 tool | `restart_connector`, `get_consumer_lag` |
| `workflow_action` | FastAPI workflow 내부 상태 전환 또는 추가 수집 지시 | `collect_additional_evidence`, `collect_connector_trace` |
| `composite_action` | 여러 tool 후보로 분해해야 하는 조치 의도 | `reduce_pipeline_pressure`, `pause_low_priority_pipeline` |
| `notification` | 운영자 알림 | `send_operator_notification` |
| `escalation` | 고객사/플랫폼/운영자에게 evidence 전달 | `escalate_to_customer_owner` |

Policy Guard는 현재 `action_type` + `risk`만 `policy_matrix.lookup(...)`으로 판정한다. `runtime_tool` allowlist 검증과 미등록 tool 차단은 실행 시점의 `ToolClientRegistry.call_tool(...)`에서 일어난다. `workflow_action`, `composite_action`, `notification`, `escalation`은 action catalog/template 기반 후보로 남고, 필요하면 구체적인 runtime tool이나 Spring Boot workflow support API로 변환된다.

### 3. Tool 실행 구조

```text
Agent
  -> Tool Client Registry
  -> Spring Boot Operations API
  -> Resource Adapter
  -> Runtime / Observability
```

LLM이 생성할 수 있는 것은 tool call 의도와 parameter 초안이다. 실제 호출 여부는 Supervisor와 Tool Client Registry가 검증한다.

### 4. 공통 Tool Context

모든 tool call은 공통 context를 가진다.

```json
{
  "run_id": "run_20260601_001",
  "step_id": "step_004",
  "agent_name": "Retrieval",
  "project_id": "proj_001",
  "user_id": "user_001",
  "incident_id": "inc_001",
  "pipeline_id": "daily_user_sync",
  "request_id": "req_20260601_001"
}
```

FastAPI는 이 context를 Spring Boot header로 변환한다. Spring request body/query는 tool params에서 만든다.

### 5. 공통 Tool Result

Tool result는 Agent State에 들어가기 전에 표준화한다.

```json
{
  "tool_name": "get_consumer_lag",
  "status": "success",
  "risk": "read_only",
  "requires_approval": false,
  "summary": "orders-consumer lag is elevated on partition 3",
  "evidence_ids": ["ev_metric_001"],
  "audit_event_id": "audit_20260601_001",
  "error": null
}
```

실패 result:

```json
{
  "tool_name": "restart_connector",
  "status": "blocked",
  "risk": "high",
  "requires_approval": true,
  "summary": "approval is required before restarting connector",
  "evidence_ids": [],
  "audit_event_id": "audit_20260601_002",
  "error": {
    "code": "APPROVAL_REQUIRED",
    "message": "restart_connector requires approval"
  }
}
```

### 6. Tool 위험도 분류

| Risk | 설명 | 기본 정책 |
| --- | --- | --- |
| `read_only` | 상태 조회, 로그/메트릭/trace 조회 | 자동 허용 |
| `low` | 실행 영향이 없거나 내부 State만 변경 | runtime/workflow/notification은 allow, escalation은 approval |
| `medium` | 제한적 runtime 상태 변경 | runtime/workflow은 change management, composite/escalation은 approval, notification은 allow |
| `high` | 데이터 재처리, rollback, 영향 범위 큼 | runtime/workflow/composite/notification/escalation은 approval |
| `forbidden` | 삭제, shell, 임의 SQL, secret 조회 | deny |

### 7. Agent별 Tool 사용 권한

| Agent | 허용 tool/action |
| --- | --- |
| Router | 없음 또는 run metadata 조회 |
| Planner | tool catalog metadata 조회, 추가 evidence 계획 |
| Retrieval | read-only runtime tool, workflow evidence collection |
| Classifier | evidence 조회 결과 참조 |
| RCA | evidence 조회 결과 참조, 추가 read 요청 |
| Remediation | action template 조회, mutation 실행 금지 |
| Policy Guard | policy/runbook/action catalog 조회, approval 필요 여부 판단 |
| Executor | runtime tool 실행. 현재 FastAPI executor는 `X-Idempotency-Key`만 만들고 `X-Approval-Id`를 Spring으로 전파하지 않아, Spring mutation은 approval 누락 시 403 `APPROVAL_REQUIRED`로 차단된다 |
| Verifier | read-only after-check tool |
| Report | tool 직접 호출 금지 |

Report는 tool을 직접 호출하지 않는다. 검증된 State만 사용한다.

### 8. Runtime Tool Catalog

현재 FastAPI `ToolClientRegistry`에는 16개 논리 tool definition이 있다(#373: `get_connector_task_trace` 추가). 이 표는 FastAPI registry의 실제 목록이고, Spring `GET /internal/ops/admin/tool-catalog`와 동일하지 않다.

| Tool | Operation | Method | Path template | Risk | Approval |
| --- | --- | --- | --- | --- | --- |
| `search_logs` | `search_logs` | `POST` | `/internal/ops/projects/{project_id}/observability/logs/search` | `read_only` | no |
| `get_metrics` | `query_metrics` | `GET` | `/internal/ops/projects/{project_id}/observability/metrics` | `read_only` | no |
| `get_deployments` | `get_recent_changes` | `GET` | `/internal/ops/projects/{project_id}/pipelines/changes` | `read_only` | no |
| `get_connector_status` | `get_connector_status` | `GET` | `/internal/ops/projects/{project_id}/kafka/connectors/{connector_name}/status` | `read_only` | no |
| `get_consumer_lag` | `get_consumer_lag` | `GET` | `/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag` | `read_only` | no |
| `get_kafka_lag` | `get_consumer_lag` | `GET` | `/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/lag` | `read_only` | no. Alias for `get_consumer_lag` |
| `list_project_pipelines` | `list_project_pipelines` | `GET` | `/internal/ops/projects/{project_id}/pipelines` | `read_only` | no |
| `get_pipeline_topology` | `get_pipeline_topology` | `GET` | `/internal/ops/projects/{project_id}/pipelines/{pipeline_id}/topology` | `read_only` | no |
| `get_incident_summary` | `get_incident_summary` | `GET` | `/internal/ops/incidents/{incident_id}/summary` | `read_only` | no |
| `restart_connector` | `restart_connector` | `POST` | `/internal/ops/projects/{project_id}/connectors/{connector_name}/restart` | `high` | yes |
| `pause_connector` | `pause_connector` | `POST` | `/internal/ops/projects/{project_id}/connectors/{connector_name}/pause` | `medium` | yes |
| `resume_connector` | `resume_connector` | `POST` | `/internal/ops/projects/{project_id}/connectors/{connector_name}/resume` | `medium` | yes |
| `restart_consumer_group` | `restart_consumer_group` | `POST` | `/internal/ops/projects/{project_id}/kafka/consumer-groups/{consumer_group}/restart` | `high` | yes |
| `get_traces` | `query_traces` | `GET` | `/internal/ops/projects/{project_id}/connectors/{connector_name}/traces` | `read_only` | no |
| `get_connector_task_trace` | `get_connector_task_trace` | `GET` | `/internal/ops/projects/{project_id}/connectors/{connector_name}/task-trace` | `read_only` | no |
| `get_alerts` | `list_alerts` | `GET` | `/internal/ops/projects/{project_id}/observability/alerts` | `read_only` | no |

Spring runtime read catalog는 이 중 구현된 read endpoint 8개만 반환한다.

| Spring catalog operation | FastAPI registry 대응 |
| --- | --- |
| `get_consumer_lag` | `get_consumer_lag`, `get_kafka_lag` alias |
| `search_logs` | `search_logs` |
| `query_traces` | `get_traces` |
| `list_alerts` | `get_alerts` |
| `get_incident_summary` | `get_incident_summary` |
| `list_project_pipelines` | `list_project_pipelines` |
| `get_pipeline_topology` | `get_pipeline_topology` |
| `get_connector_status` | `get_connector_status` |

현재 path/operation 매핑과 result schema는 완전히 호환되지 않는다. FastAPI `ToolResult`는 strict schema를 검증하므로 그대로 호출하면 일부 read tool은 response parsing 실패가 날 수 있다. 예: Spring `get_consumer_lag`는 `consumerGroup`/`totalLag`/`source`를 반환하지만 FastAPI schema는 `consumer_group`/`total_lag`와 optional `partitions`를 요구한다. Spring `search_logs`는 `logs`/`total`/`note`를 반환하지만 FastAPI schema는 `match_count`/`summary`를 요구한다. Spring `get_connector_status`는 `connectorName`/`connectorState`/`tasks[].id`를 반환하지만 FastAPI schema는 `connector_name`/`state`/`tasks[].task_id`를 요구한다. Spring `get_incident_summary`는 `incidentId`/`status`/`note`만 반환하지만 FastAPI schema는 `incident_id`/`severity`/`trigger_event`/`related_event_count`/`grouping_key`를 요구한다. Spring `list_project_pipelines`는 bare `PipelineResponse[]`를 반환하지만 FastAPI schema는 `{"pipelines": [...]}` object를 요구한다. Spring `get_pipeline_topology`는 `pipelineId`/`sourceDbId`/`topic`/`sourceConnector`/`sinkConnector` 형태지만 FastAPI schema는 `pipeline_id`/nested `source`/`topics`/connector `cr_name` 형태를 요구한다.

`get_metrics`와 `get_deployments`는 FastAPI registry에는 남아 있지만 현재 Spring `tool-catalog`와 controller endpoint에는 없다. Agent가 사용할 구현 가능 tool로 간주하면 안 된다.

### 9. Mutation Runtime Tool Catalog

현재 Spring에 구현된 mutation은 Kafka Connect 계열 4개뿐이다. Spring endpoint는 모두 `X-Agent-Run-Id`, `X-Agent-Step-Id`, `X-Idempotency-Key`, `X-Approval-Id`가 필요하고 `ApprovalValidator`와 `IdempotencyGuard`를 통과해야 한다. FastAPI executor의 현재 `ToolContext`는 `X-Approval-Id`를 전송하지 않는다.

| Agent 논리 tool | Spring operation | 정책 | 현재 구현 |
| --- | --- | --- | --- |
| `restart_connector` | `restart_connector` | approval | 구현됨 |
| `pause_connector` | `pause_connector` | approval | 구현됨 |
| `resume_connector` | `resume_connector` | approval | 구현됨 |
| `restart_consumer_group` | `restart_consumer_group` | approval | 구현됨. `connect-` prefix의 Kafka Connect-managed sink connector consumer group만 지원 |

현재 구현에 없는 mutation:

| Tool/operation | 상태 |
| --- | --- |
| `restart_connector_task` | endpoint 없음 |
| `scale_consumer_deployment`, `rollout_restart_deployment`, `rollback_deployment` | endpoint 없음 |
| `pause_pipeline`, `resume_pipeline` agent mutation | platform user-facing API는 있으나 `/internal/ops` agent mutation endpoint 없음 |
| `backfill_pipeline`, `rollback_pipeline` | endpoint 없음 |
| rebalance proposal/approve/refresh | endpoint 없음 |

### 10. Workflow / Notification Action Catalog

다음 action은 runtime tool이 아니거나, 여러 read/mutation tool로 분해되는 action이다.

| Action | action_type | 처리 |
| --- | --- | --- |
| `collect_source_timeout_evidence` | `workflow_action` | 설계상 `search_logs`, `get_connector_status`, `get_pipeline_topology`, `list_project_pipelines` 조합이지만 현재 `get_pipeline_topology`/`list_project_pipelines`는 Spring result shape과 FastAPI schema가 맞지 않아 adapter 없이는 직접 실행 후보로 보면 안 된다 |
| `collect_connector_trace` | `workflow_action` | `get_traces`(Tempo 분산 trace), `get_connector_task_trace`(task 예외 stack trace, #373), `search_logs`, `get_connector_status` 실행 계획으로 변환 |
| `collect_lag_evidence` | `workflow_action` | `get_consumer_lag`, `get_connector_status`, `search_logs`, `get_alerts` 실행 계획으로 변환 |
| `collect_additional_evidence` | `workflow_action` | Planner가 현재 registry에 있는 read-only tool만 사용해 추가 retrieval plan 생성 |
| `reduce_pipeline_pressure` | `composite_action` | 현재 mutation 가능 후보는 `pause_connector`, `resume_connector`, `restart_consumer_group`로 제한 |
| `escalate_to_operator` | `escalation` | 확정 불가 상태와 evidence gap을 운영자에게 전달 |

아래 action/tool은 catalog 설계에는 남을 수 있지만 현재 FastAPI registry 또는 Spring endpoint에 없다: schema change 조회, broker/topic metric, pod status/log/event, deployment scale, pipeline pause/resume agent mutation, ticket 생성 notification API.

`composite_action`은 Executor가 바로 실행하지 않는다. Remediation 또는 Policy Guard 단계에서 구체적인 `runtime_tool` 후보로 분해되거나, 사람이 선택해야 하는 action으로 남긴다.

### 11. 금지 Tool

다음 tool은 등록하지 않는다.

| 금지 tool | 이유 |
| --- | --- |
| `exec_pod_shell` | shell 권한 노출 |
| `run_sql` | 고객사 DB 직접 조작 위험 |
| `delete_topic` | 데이터 삭제 위험 |
| `delete_pod` | 우회적 장애 유발 가능 |
| `read_secret` | secret 원문 노출 |
| `patch_arbitrary_manifest` | LLM 생성 manifest 적용 위험 |
| `truncate_table` | 데이터 손실 |
| `update_connector_config_raw` | 영향 범위가 큰 config 변경 |

필요한 경우 사람이 별도 runbook과 변경관리 절차로 수행한다.

### 12. Tool Client Registry

FastAPI는 중앙 registry를 둔다.

```text
ToolClientRegistry
  -> tool name validation
  -> action type validation 없음(현재 `ToolDefinition`에 `action_type`이 없다)
  -> parameter schema validation
  -> risk lookup
  -> approval requirement lookup
  -> Spring Boot API mapping
  -> timeout / retry policy
  -> result normalization
```

Registry가 없으면 endpoint 호출이 여러 Agent에 흩어지고, 정책과 naming이 쉽게 어긋난다.

### 13. Tool Call 검증 순서

Read-only tool:

1. tool name allowlist 확인
2. parameter schema 확인
3. project scope 확인용 header 구성
4. Spring Boot API 호출
5. evidence reference를 State에 append

#### 13.1 Read-only tool 병렬 실행

Retrieval이 받은 read plan은 현재 `retrieval.py`에서 각 tool coroutine을 만들고 `asyncio.gather(...)`로 한 번에 실행한다. `depends_on` 기반 dependency graph, concurrency limit, dependent tool sequential execution은 구현되어 있지 않다.

- 각 tool은 [§15](contract/contract-workflow-control.md#15-contract-workflow-control)의 개별 timeout/retry를 그대로 가진다. 한 tool이 timeout/실패해도 나머지 결과는 살리고, 실패는 evidence gap으로 RCA에 전달한다(부분 수집 허용).
- 완료된 tool은 즉시 `tool_call_completed`·`evidence_collected` event로 스트리밍한다. 전체 fan-out 완료를 기다려 한 번에 반영하지 않는다.
- mutation tool은 병렬 대상이 아니다(Executor가 정해진 순서로 단건 실행).

병렬화의 목적은 retrieval 단계 wall-clock을 "tool 합계"가 아니라 "가장 느린 tool"에 가깝게 줄이는 것이다.

Mutation tool:

1. tool name allowlist 확인
2. parameter schema 확인
3. `X-Idempotency-Key`를 포함한 `ToolContext` 준비. 현재 registry는 `requires_approval=true` tool을 idempotency key 없이 호출하면 blocked result를 반환한다
4. Spring Boot API 호출. approval id, params hash, ownership, single-use는 Spring Boot가 최종 검증한다
5. execution result를 State에 append
6. Verifier를 실행

Workflow/notification/escalation action:

1. action catalog 등록 여부 확인
2. action type 확인
3. 필요한 경우 read-only tool plan 또는 Spring Boot workflow support 요청으로 변환
4. audit와 evidence reference를 State에 append

FastAPI에서 통과해도 Spring Boot가 같은 검증을 다시 수행한다.

### 14. Parameter Hash

Approval은 parameter와 묶인다. 승인 후 parameter가 바뀌면 실행하면 안 된다.

Hash 대상 예시:

```json
{
  "tool_name": "restart_connector",
  "project_id": "proj_001",
  "connector_name": "daily-user-sync-source"
}
```

정규화된 JSON을 SHA-256으로 hash한다. key order, whitespace, null 처리 규칙은 구현에서 고정한다.

Spring Boot mutation은 같은 operation/params hash에 대해서만 idempotency replay를 허용한다. 같은 key라도 connector name 또는 operation이 달라지면 `CONFLICT`다.

### 15. Retry와 Timeout

| 호출 유형 | Timeout | Retry |
| --- | --- | --- |
| Spring Operations API call | `spring_ops_timeout_seconds` 기본 10s | 현재 `ToolClientRegistry.call_tool(...)`는 `ToolStatus.TIMEOUT` result를 반환하고 자동 retry하지 않음 |

Metric query, Kubernetes status, workflow notification은 설계 항목이지만 현재 Spring endpoint/registry로 실행 가능한 항목이 아니다.

Mutation의 자동 재시도는 중복 실행 위험이 있으므로 기본 금지한다. 현재 registry 구현은 timeout 후 별도 after-check retry를 자동 실행하지 않는다.

### 16. Evidence 처리

Tool output은 세 계층으로 나눈다.

| 계층 | 저장 위치 |
| --- | --- |
| raw result | Evidence Store |
| evidence metadata | Agent State |
| user-facing summary | Report |

Tool result에 raw log, secret, connection string, stack trace 전체를 넣지 않는다. 필요한 경우 redacted summary와 `store_ref`만 반환한다.

### 17. 대표 시나리오

#### 17.1 Source timeout RCA

사용 tool:

- `search_logs`
- `get_connector_status`
- `get_pipeline_topology`
- `list_project_pipelines`

RCA는 connector 상태, pipeline topology, 로그 요약을 함께 확인한다. 현재 Spring endpoint가 없는 metric/dependency/deployment tool은 이 시나리오의 구현 가능 tool로 쓰지 않는다.

#### 17.2 Consumer lag 증가

사용 tool:

- `get_consumer_lag`
- `get_connector_status`
- `search_logs`

조치 후보:

- `restart_consumer_group`
- `pause_connector` 또는 `resume_connector`는 connector 상태와 approval이 맞을 때만

Mutation은 모두 approval과 idempotency 대상이다.

#### 17.3 Connector task 실패

사용 tool:

- `get_connector_status`
- `get_traces`
- `search_logs`
- `get_incident_summary`

조치 후보:

- `restart_connector`

승인 없이는 실행하지 않는다.

### 18. Versioning

Tool catalog는 version을 둔다.

```json
{
  "tool_catalog_version": "2026-06-01",
  "deprecated_aliases": {
    "get_kafka_lag": "get_consumer_lag"
  }
}
```

Tool 이름 변경은 바로 제거하지 않고 alias 기간을 둔다. 단, 새 문서와 prompt에는 canonical 이름만 사용한다.

### 19. 결론

Tool 설계의 핵심은 LLM에게 “많은 권한”을 주는 것이 아니라, Agent가 안전하게 요청할 수 있는 운영 의도를 좁게 정의하는 것이다.

FastAPI는 tool call을 정규화하고, Spring Boot는 최종 실행 가능 여부를 판단한다. 이 경계가 유지되면 MCP 없이도 안전하고 재현 가능한 운영 Agent를 만들 수 있다.

---

## 5. MCP Decision


### 1. 결론

Bifrost v1에서는 **MCP Server를 구성하지 않는다**.

이 결정은 MCP가 부적절해서가 아니라, 현재 프로젝트의 핵심 문제가 tool protocol 표준화가 아니기 때문이다. v1에서 더 중요한 것은 approval, 권한, idempotency가 걸린 내부 운영 API다. audit/evidence reference는 현재 Spring mutation 응답에 연결되어 있지 않은 구현 보강 대상이다.

권장 구조는 다음이다.

```text
FastAPI Agent Server
  -> Spring Boot Operations Backend
  -> Fabric8 / Kafka AdminClient / Kafka Connect REST / Prometheus / Loki / Tempo / KafkaRebalance
```

Spring Boot Operations Backend가 Agent-facing Tool Adapter 역할을 한다. 별도의 MCP Server, Spring Boot MCP endpoint, MCP sidecar는 두지 않는다.

### 2. MCP가 필요하지 않은 이유

#### 2.1 운영 로직은 이미 Spring Boot에 있어야 한다

Kubernetes와 Kafka 리소스 제어에는 다음 로직이 필요하다.

- project scope 검증
- resource ownership 검증
- approval 검증
- change ticket 검증
- idempotency 검증
- audit log
- before/after snapshot 저장
- 실제 Fabric8/Kafka/Prometheus 호출

이 로직은 Spring Boot Operations Backend에 모이는 것이 맞다. MCP Server를 별도로 두면 결국 Spring Boot API를 한 번 더 감싸는 얇은 proxy가 된다.

#### 2.2 Agent가 필요한 것은 표준 protocol보다 좁은 권한이다

Agent에게 필요한 것은 “아무 tool이나 발견해서 호출하는 능력”이 아니라 “허용된 운영 의도만 호출하는 능력”이다.

따라서 tool discovery보다 중요한 것은 다음이다.

- tool allowlist
- 고정 schema
- parameter validation
- approval/change management gate
- audit event
- evidence reference
- retry와 timeout policy

이 요구사항은 내부 REST API와 Tool Client Registry로 충분히 충족된다.

#### 2.3 MCP는 책임 경계를 흐릴 수 있다

MCP Server가 runtime resource를 직접 만지면 다음 질문이 생긴다.

| 질문 | v1 답 |
| --- | --- |
| 최종 정책 집행자는 누구인가 | Spring Boot |
| approval을 누가 검증하는가 | Spring Boot |
| audit 기준점은 어디인가 | Spring Boot |
| Fabric8 credential은 어디에 있는가 | Spring Boot |
| Kafka Admin 권한은 어디에 있는가 | Spring Boot |

MCP를 끼우면 이 경계가 하나 더 생긴다. v1에서는 경계를 줄이는 편이 낫다.

### 3. 최종 책임 분리

| 계층 | 역할 |
| --- | --- |
| FastAPI Agent | workflow, LLM orchestration, State, tool wrapper |
| Spring Boot Operations Backend | policy, approval, audit, runtime operation |
| Runtime Infra | Kafka, Kubernetes, Prometheus, Loki, Tempo, Strimzi |

FastAPI tool wrapper는 Spring Boot API 호출만 한다. Fabric8, Kafka AdminClient, Prometheus client는 FastAPI에 두지 않는다.

### 4. MCP를 도입해도 되는 조건

다음 조건이 생기면 MCP를 Phase 3 이후에 재검토한다.

| 조건 | 의미 |
| --- | --- |
| 외부 MCP client 필요 | Bifrost 외부의 Agent나 IDE가 같은 tool catalog를 써야 함 |
| tool ecosystem 공유 필요 | 여러 Agent 제품이 동일 tool registry를 공유해야 함 |
| read-only knowledge tool 분리 | 문서 검색, runbook 검색처럼 안전한 조회 tool을 독립 제공 |
| 조직 표준화 | 사내 Agent platform이 MCP를 표준 gateway로 채택 |

단, 재검토하더라도 상태 변경 tool을 MCP에 직접 열지 않는다. MCP는 read-only 또는 Spring Boot API proxy 수준으로 제한한다.

### 5. MCP를 도입한다면 지켜야 할 제한

향후 MCP를 추가하더라도 다음 제한은 유지한다.

1. MCP Server는 runtime credential을 직접 갖지 않는다.
2. mutation은 Spring Boot Operations Backend를 통해서만 실행한다.
3. approval과 change ticket 검증은 Spring Boot가 한다.
4. MCP tool은 Spring Boot operation을 감싸는 thin adapter여야 한다.
5. audit 기준점은 Spring Boot audit event다.
6. LLM이 MCP tool output을 근거로 삼을 때도 Evidence Store reference를 사용한다.

즉, MCP는 control plane이 아니라 integration boundary다.

### 6. 문서 기준

현재 v1 설계에서는 다음 문서를 기준으로 구현한다.

| 문서/섹션 | 역할 |
| --- | --- |
| [backend 개요](../../README.md) | FastAPI/Spring Boot 책임 분리 |
| [§2](server-design.md#2-server-design) Server Design | FastAPI Agent Server 설계 |
| [§3](../../api/fastapi.md) API Reference | Frontend-facing FastAPI API |
| [§1](agent-principles.md#1-agent-principles) Agent Principles | Agent workflow와 판단 원칙 |
| [§6](catalog/catalog-failure-types.md#6-catalog-failure-types)~[§12](catalog/catalog-policy-matrix.md#12-catalog-policy-matrix) Catalogs | 장애 유형, RCA 후보, evidence, runbook, policy |
| [§13](contract/contract-agent-roles.md#13-contract-agent-roles)~[§17](contract/contract-output-schemas.md#17-contract-output-schemas) Contracts | Agent 역할, State, workflow, streaming, output schema |
| [Spring Boot DETAILS](../backend-springboot/overview.md) | Operations Backend 설계 + 내부 운영 API |
| [§4](#4-tool-catalog) Tool Catalog | Agent tool catalog와 mapping |
| [Infra DETAILS](../infra.md) | runtime infra와 권한 경계 |

### 7. 결론

구현 복잡도를 제외하고 보더라도, 이 프로젝트의 v1 핵심 경로에는 MCP보다 Spring Boot Operations API가 더 논리적으로 맞다.

MCP의 장점은 표준화와 tool discovery다. 하지만 Bifrost v1의 우선순위는 안전한 운영 제어, 승인, 감사, evidence 기반 RCA다. 따라서 MCP는 제외하고, 필요성이 생기면 read-only 또는 proxy 용도로 재검토한다.

---
