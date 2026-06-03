# FastAPI Agent Server — Tool Catalog & MCP Decision

> 요약은 [overview.md](./overview.md). 이 파일은 Agent tool catalog/매핑(§4)과 MCP 결정(§5)을 담는다.

## 4. Tool Catalog


### 1. 목적

이 문서는 Agent가 사용할 수 있는 논리 tool, workflow action, Spring Boot Operations API 매핑을 정의한다.

핵심 원칙은 다음과 같다.

1. LLM은 API path를 직접 만들지 않는다.
2. Agent는 논리 tool 이름과 action type만 사용한다.
3. FastAPI Tool Client Registry가 논리 tool을 Spring Boot operation으로 매핑한다.
4. Spring Boot가 최종 권한, 정책, 승인, 감사, idempotency를 검증한다.
5. Tool output은 raw content가 아니라 evidence reference 중심으로 반환한다.

API endpoint 상세는 Spring Boot API를 기준으로 한다. 이 문서는 섹션 번호 대신 API 영역명으로 참조한다.

### 2. Tool과 Action의 차이

Runbook의 `Action`이 항상 실행 tool은 아니다.

| 구분 | 의미 | 예시 |
| --- | --- | --- |
| `runtime_tool` | Spring Boot Operations API로 실행되는 단일 tool | `restart_connector_task`, `scale_consumer_deployment` |
| `workflow_action` | FastAPI workflow 내부 상태 전환 또는 추가 수집 지시 | `collect_additional_evidence`, `collect_connector_trace` |
| `composite_action` | 여러 tool 후보로 분해해야 하는 조치 의도 | `reduce_pipeline_pressure`, `pause_low_priority_pipeline` |
| `notification` | 운영자 알림 | `send_operator_notification` |
| `escalation` | 고객사/플랫폼/운영자에게 evidence 전달 | `escalate_to_customer_owner` |

Policy Guard는 `runtime_tool`만 tool allowlist로 검증한다. `workflow_action`, `composite_action`, `notification`, `escalation`은 action catalog 기준으로 검증하고, 필요하면 구체적인 runtime tool이나 Spring Boot workflow support API로 변환한다.

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

FastAPI는 이 context를 Spring Boot header와 body로 변환한다.

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
  "tool_name": "restart_connector_task",
  "status": "blocked",
  "risk": "medium",
  "requires_approval": true,
  "summary": "approval is required before restarting connector task",
  "evidence_ids": [],
  "audit_event_id": "audit_20260601_002",
  "error": {
    "code": "APPROVAL_REQUIRED",
    "message": "restart_connector_task requires approval"
  }
}
```

### 6. Tool 위험도 분류

| Risk | 설명 | 기본 정책 |
| --- | --- | --- |
| `read_only` | 상태 조회, 로그/메트릭/trace 조회 | 자동 허용 |
| `low` | 실행 영향이 없거나 내부 State만 변경 | 자동 또는 정책 허용 |
| `medium` | 제한적 runtime 상태 변경 | approval 필요 |
| `high` | 데이터 재처리, rollback, 영향 범위 큼 | change management 필요 |
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
| Executor | 승인된 runtime tool 실행 |
| Verifier | read-only after-check tool |
| Report | tool 직접 호출 금지 |

Report는 tool을 직접 호출하지 않는다. 검증된 State만 사용한다.

### 8. Read-only Runtime Tool Catalog

#### 8.1 Observability

| Agent 논리 tool | Spring Boot operation | API 영역 |
| --- | --- | --- |
| `get_pipeline_logs` | `search_logs` | Observability API |
| `get_metrics` | `query_metrics` | Observability API |
| `get_traces` | `query_traces` | Observability API |
| `get_alerts` | `list_alerts` | Observability API |
| `get_alert_detail` | `get_alert_detail` | Observability API |

#### 8.2 Pipeline / Change

| Agent 논리 tool | Spring Boot operation | API 영역 |
| --- | --- | --- |
| `get_deployments` | `get_recent_changes` | Pipeline API |
| `get_airflow_task_status` | `get_pipeline_task_status` | Pipeline API |
| `get_schema_changes` | `get_recent_schema_changes` | Schema Registry API |

`get_airflow_task_status`는 이름이 Airflow에 묶여 있지만, v1에서는 pipeline task status 조회의 legacy alias로 사용한다. 구현 operation 이름은 `get_pipeline_task_status`로 유지한다.

#### 8.3 Kafka / Kafka Connect

| Agent 논리 tool | Spring Boot operation | API 영역 |
| --- | --- | --- |
| `list_kafka_clusters` | `list_kafka_clusters` | Kafka Cluster API |
| `get_broker_metrics` | `get_broker_metrics` | Kafka Cluster API |
| `list_topics` | `list_topics` | Kafka Topic API |
| `get_topic_detail` | `get_topic_detail` | Kafka Topic API |
| `get_topic_metrics` | `get_topic_metrics` | Kafka Topic API |
| `list_consumer_groups` | `list_consumer_groups` | Kafka Consumer Group API |
| `get_consumer_lag` | `get_consumer_lag` | Kafka Consumer Group API |
| `get_consumer_rebalance_events` | `get_consumer_rebalance_events` | Kafka Consumer Group API |
| `list_connectors` | `list_connectors` | Kafka Connect API |
| `get_connector_status` | `get_connector_status` | Kafka Connect API |
| `get_connector_config` | `get_connector_config` | Kafka Connect API |
| `get_connector_task_trace` | `get_connector_task_trace` | Kafka Connect API |
| `get_rebalance_status` | `get_rebalance_status` | Strimzi / Rebalance API |

`get_kafka_lag`는 legacy alias로만 허용한다. 새 문서와 구현에서는 `get_consumer_lag`를 표준 이름으로 사용한다.

#### 8.4 Kubernetes

| Agent 논리 tool | Spring Boot operation | API 영역 |
| --- | --- | --- |
| `get_deployment_health` | `get_deployment_health` | Kubernetes API |
| `list_pods` | `list_pods` | Kubernetes API |
| `get_pod_status` | `get_pod_status` | Kubernetes API |
| `get_pod_logs` | `get_pod_logs` | Kubernetes API |
| `get_k8s_events` | `get_k8s_events` | Kubernetes API |
| `get_pvc_status` | `get_pvc_status` | Kubernetes API |

#### 8.5 Dependency

| Agent 논리 tool | Spring Boot operation | API 영역 |
| --- | --- | --- |
| `get_db_connection_status` | `get_dependency_connection_status` | Dependency API |
| `get_dependency_latency` | `get_dependency_latency` | Dependency API |
| `get_dependency_changes` | `get_dependency_recent_changes` | Dependency API |

이 tool은 고객사 DB 내부를 조회하거나 SQL을 실행하지 않는다. 파이프라인이 관측한 connection timeout, reachability, error rate, pool 상태만 조회한다.

### 9. Mutation Runtime Tool Catalog

Mutation tool은 Executor만 호출할 수 있다.

#### 9.1 Kafka / Kafka Connect Mutation

| Agent 논리 tool | Spring Boot operation | API 영역 | 정책 |
| --- | --- | --- | --- |
| `restart_connector_task` | `restart_connector_task` | Kafka Connect API | approval |
| `restart_connector` | `restart_connector` | Kafka Connect API | approval |
| `pause_connector` | `pause_connector` | Kafka Connect API | approval |
| `resume_connector` | `resume_connector` | Kafka Connect API | approval |

#### 9.2 Kubernetes Mutation

| Agent 논리 tool | Spring Boot operation | API 영역 | 정책 |
| --- | --- | --- | --- |
| `scale_consumer_deployment` | `scale_deployment` | Kubernetes API | approval |
| `rollout_restart_deployment` | `rollout_restart_deployment` | Kubernetes API | approval |
| `rollback_deployment` | `rollback_deployment` | Kubernetes API | change management |

> **Bifrost 런타임 매핑 주의**: Bifrost가 소유한 consumer는 **CDC JDBC Sink connector task**(Kafka Connect)뿐이며 별도 consumer Deployment를 운영하지 않는다. 따라서 `scale_consumer_deployment`(k8s Deployment scale)는 별도 consumer 워크로드가 있을 때만 유효하고, sink 처리량 확대의 1차 레버는 connector `tasksMax` 조정·`create_rebalance_proposal`이다. 대상 Deployment가 없으면 Policy Guard/Spring이 `RESOURCE_NOT_FOUND`로 차단한다.

#### 9.3 Pipeline Mutation

| Agent 논리 tool | Spring Boot operation | API 영역 | 정책 |
| --- | --- | --- | --- |
| `pause_pipeline` | `pause_pipeline` | Pipeline API | approval |
| `resume_pipeline` | `resume_pipeline` | Pipeline API | approval |
| `backfill_pipeline` | `create_backfill` | Pipeline API | change management |
| `rollback_pipeline` | `create_rollback` | Pipeline API | change management |

Backfill과 rollback은 v1 초기에는 비활성화하고, 변경관리 체계가 준비된 뒤 연다.

> 파이프라인 **생성**은 플랫폼 마법사(FR-004) 전용이며 agent tool로 노출하지 않는다(v1). agent는 조회·Pause/Resume·backfill/rollback만 수행한다.

#### 9.4 Rebalance Mutation

| Agent 논리 tool | Spring Boot operation | API 영역 | 정책 |
| --- | --- | --- | --- |
| `create_rebalance_proposal` | `create_rebalance_proposal` | Strimzi / Rebalance API | approval 또는 사전 정책 |
| `approve_rebalance` | `approve_rebalance` | Strimzi / Rebalance API | approval |
| `refresh_rebalance` | `refresh_rebalance` | Strimzi / Rebalance API | approval |

### 10. Workflow / Notification Action Catalog

다음 action은 runtime tool이 아니거나, 여러 read/mutation tool로 분해되는 action이다.

| Action | action_type | 처리 |
| --- | --- | --- |
| `collect_source_timeout_evidence` | `workflow_action` | `get_pipeline_logs`, `get_metrics`, `get_db_connection_status`, `get_airflow_task_status` 실행 계획으로 변환 |
| `collect_auth_error_evidence` | `workflow_action` | 로그, dependency change, credential rotation evidence 수집 |
| `collect_connector_trace` | `workflow_action` | `get_connector_task_trace`, `get_pipeline_logs`, `get_connector_status` 실행 계획으로 변환 |
| `collect_schema_changes` | `workflow_action` | `get_schema_changes`, compatibility check evidence 수집 |
| `collect_broker_metrics` | `workflow_action` | `get_broker_metrics`, `get_topic_metrics`, `get_metrics` 실행 계획으로 변환 |
| `collect_sink_timeout_evidence` | `workflow_action` | sink dependency timeout, write latency, connector log evidence 수집 |
| `collect_sink_write_metrics` | `workflow_action` | sink write latency, retry/backoff metric 수집 |
| `collect_pod_status` | `workflow_action` | `get_pod_status`, `get_k8s_events`, `get_pod_logs` 실행 계획으로 변환 |
| `collect_memory_metrics` | `workflow_action` | pod/container memory metric 수집 |
| `collect_recent_changes` | `workflow_action` | deployment/config/schema/image change evidence 수집 |
| `collect_additional_evidence` | `workflow_action` | Planner가 추가 retrieval plan 생성 |
| `pause_non_critical_pipeline` | `composite_action` | 구체적인 `pause_pipeline` 후보와 영향 범위로 분해 |
| `pause_low_priority_pipeline` | `composite_action` | 낮은 우선순위 pipeline을 선택한 뒤 `pause_pipeline`으로 분해 |
| `reduce_pipeline_pressure` | `composite_action` | `pause_pipeline`, `pause_connector`, scale-out 후보 중 정책상 가능한 조치로 분해 |
| `send_operator_notification` | `notification` | Spring Boot Workflow Support API 또는 알림 adapter로 전송 |
| `create_ticket` | `notification` | Spring Boot Workflow Support API의 ticket endpoint로 외부 티켓 생성 |
| `escalate_to_customer_owner` | `escalation` | evidence summary를 고객사 owner에게 전달 |
| `escalate_credential_rotation` | `escalation` | credential owner에게 rotation failure evidence 전달 |
| `escalate_platform_capacity` | `escalation` | platform team에 capacity evidence 전달 |
| `escalate_to_operator` | `escalation` | 확정 불가 상태와 evidence gap을 운영자에게 전달 |

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
  -> action type validation
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

Retrieval이 받은 plan에서 **서로 입력 의존이 없는 read-only tool은 동시에 실행한다.** Registry는 plan을 의존 그래프로 보고, 독립 노드를 concurrency 한도(예: 5~8 동시) 안에서 fan-out한다. 의존이 있는 tool(앞 tool 결과가 parameter가 되는 경우)만 순차로 둔다.

- 각 tool은 [§15](contracts.md#15-contract-workflow-control)의 개별 timeout/retry를 그대로 가진다. 한 tool이 timeout/실패해도 나머지 결과는 살리고, 실패는 evidence gap으로 RCA에 전달한다(부분 수집 허용).
- 완료된 tool은 즉시 `tool_call_completed`·`evidence_collected` event로 스트리밍한다. 전체 fan-out 완료를 기다려 한 번에 반영하지 않는다.
- mutation tool은 병렬 대상이 아니다(Executor가 정해진 순서로 단건 실행).

병렬화의 목적은 retrieval 단계 wall-clock을 "tool 합계"가 아니라 "가장 느린 tool"에 가깝게 줄이는 것이다.

Mutation tool:

1. tool name allowlist 확인
2. action id와 State의 approved action 매칭 확인
3. approval 또는 change ticket 존재 확인
4. params hash 확인
5. `X-Idempotency-Key` 생성
6. Spring Boot API 호출
7. before/after evidence reference 저장
8. execution result를 State에 append
9. Verifier를 실행

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
  "tool_name": "scale_consumer_deployment",
  "project_id": "proj_001",
  "namespace": "bifrost-system",
  "deployment_name": "orders-consumer",
  "replicas": 6
}
```

정규화된 JSON을 SHA-256으로 hash한다. key order, whitespace, null 처리 규칙은 구현에서 고정한다.

예시의 `namespace`는 project registry가 반환한 실제 workload namespace를 사용한다. 문서 예시는 `bifrost-system`이지만, 운영에서는 project별 namespace 또는 workload label을 기준으로 Spring Boot가 다시 검증한다.

### 15. Retry와 Timeout

| Tool 유형 | Timeout | Retry |
| --- | --- | --- |
| log search | 10s | 1회 |
| metric query | 10s | 1회 |
| trace query | 10s | 1회 |
| Kafka status 조회 | 5s | 2회 |
| Kubernetes status 조회 | 5s | 2회 |
| workflow/notification | 10s | idempotent이면 1회 |
| mutation | 15s | 자동 재시도 금지, idempotency replay만 허용 |

Mutation의 자동 재시도는 중복 실행 위험이 있으므로 기본 금지한다. timeout 후 상태 확인 read tool을 실행해 실제 반영 여부를 확인한다.

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

- `get_pipeline_logs`
- `get_metrics`
- `get_db_connection_status`
- `get_airflow_task_status`
- `get_deployments`

RCA는 source read timeout과 pipeline extract 단계 지연을 함께 확인한다. sink DB 지표를 근거로 source 장애를 결론내지 않는다.

#### 17.2 Consumer lag 증가

사용 tool:

- `get_consumer_lag`
- `get_topic_detail`
- `get_deployment_health`
- `list_pods`
- `get_metrics`

조치 후보:

- `scale_consumer_deployment`
- `create_rebalance_proposal`

둘 다 정책 검증과 승인 대상이다.

#### 17.3 Connector task 실패

사용 tool:

- `get_connector_status`
- `get_connector_task_trace`
- `get_pipeline_logs`
- `get_k8s_events`
- `get_schema_changes`

조치 후보:

- `restart_connector_task`

승인 없이는 실행하지 않는다.

### 18. Versioning

Tool catalog는 version을 둔다.

```json
{
  "tool_catalog_version": "2026-06-01",
  "deprecated_aliases": {
    "get_kafka_lag": "get_consumer_lag",
    "get_airflow_task_status": "get_pipeline_task_status"
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

이 결정은 MCP가 부적절해서가 아니라, 현재 프로젝트의 핵심 문제가 tool protocol 표준화가 아니기 때문이다. v1에서 더 중요한 것은 승인, 감사, 권한, idempotency, evidence reference가 강하게 걸린 내부 운영 API다.

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
| [§2](principles.md#2-server-design) Server Design | FastAPI Agent Server 설계 |
| [§3](../../api/fastapi.md) API Reference | Frontend-facing FastAPI API |
| [§1](principles.md#1-agent-principles) Agent Principles | Agent workflow와 판단 원칙 |
| [§6](catalogs.md#6-catalog-failure-types)~[§12](catalogs.md#12-catalog-policy-matrix) Catalogs | 장애 유형, RCA 후보, evidence, runbook, policy |
| [§13](contracts.md#13-contract-agent-roles)~[§17](contracts.md#17-contract-output-schemas) Contracts | Agent 역할, State, workflow, streaming, output schema |
| [Spring Boot DETAILS](../backend-springboot/overview.md) | Operations Backend 설계 + 내부 운영 API |
| [§4](#4-tool-catalog) Tool Catalog | Agent tool catalog와 mapping |
| [Infra DETAILS](../infra.md) | runtime infra와 권한 경계 |

### 7. 결론

구현 복잡도를 제외하고 보더라도, 이 프로젝트의 v1 핵심 경로에는 MCP보다 Spring Boot Operations API가 더 논리적으로 맞다.

MCP의 장점은 표준화와 tool discovery다. 하지만 Bifrost v1의 우선순위는 안전한 운영 제어, 승인, 감사, evidence 기반 RCA다. 따라서 MCP는 제외하고, 필요성이 생기면 read-only 또는 proxy 용도로 재검토한다.

---
