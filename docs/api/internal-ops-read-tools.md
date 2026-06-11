# Internal Ops Read Tools

> FastAPI 논리 tool과 Spring Boot `/internal/ops/**` read endpoint의 현재 구현 상태를 기록한다. 전체 API 계약은 [Spring Boot API §6](./springboot.md#6-internal-ops-read--governance--mutation-api)가 정본이다.

## 공통 계약

내부 운영 API는 `OpsEnvelope`를 사용한다.

| Field | JSON 이름 |
| --- | --- |
| request id | `request_id` |
| audit event id | `audit_event_id` |
| required action | `error.required_action` |

`AgentHeaders`는 request id로 `X-Agent-Request-Id`를 먼저 보고, 없으면 `X-Request-Id`를 사용한다. 둘 다 없거나 unsafe하면 서버가 UUID를 생성한다.

## Runtime Tool Catalog

`GET /internal/ops/admin/tool-catalog`가 현재 Spring Boot에서 구현된 agent-callable internal-ops tool catalog를 반환한다. 현재 catalog에는 read operation과 approval-gated mutation operation이 함께 포함된다.

| Operation | Spring endpoint | Status | Result 요약 |
| --- | --- | --- | --- |
| `get_consumer_lag` | `GET /internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag` | implemented | `consumerGroup`, `totalLag`, `source` |
| `get_consumer_groups` | `GET /internal/ops/projects/{projectId}/kafka/consumer-groups` | implemented | consumer group 목록 |
| `search_logs` | `POST /internal/ops/projects/{projectId}/observability/logs/search` | implemented | `logs`, `total`, `note` |
| `query_metrics` | `GET /internal/ops/projects/{projectId}/observability/metrics` | implemented | metric query result |
| `query_traces` | `GET /internal/ops/projects/{projectId}/connectors/{connectorName}/traces` | implemented | `connector`, `traces`, optional `note` |
| `list_alerts` | `GET /internal/ops/projects/{projectId}/observability/alerts` | implemented | `alerts`, `summary` |
| `analyze_event_log` | `GET /internal/ops/projects/{projectId}/observability/events/summary` | implemented | event/incident summary |
| `get_incident_summary` | `GET /internal/ops/projects/{projectId}/incidents/{incidentId}/summary` | implemented | incident summary 또는 fallback stub |
| `list_project_pipelines` | `GET /internal/ops/projects/{projectId}/pipelines` | implemented | `PipelineResponse[]` |
| `list_pipelines` | `GET /internal/ops/projects/{projectId}/pipelines/status` | implemented | pipeline status summary list |
| `get_recent_changes` | `GET /internal/ops/projects/{projectId}/pipelines/changes` | implemented | recent pipeline changes |
| `get_pipeline_topology` | `GET /internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology` | implemented | pipeline topology |
| `get_connector_status` | `GET /internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status` | implemented | `PipelineProvisionStatus` |
| `list_connectors` | `GET /internal/ops/projects/{projectId}/kafka/connectors/status` | implemented | connector status list |
| `restart_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/restart` | implemented | approval/idempotency-gated mutation |
| `pause_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/pause` | implemented | approval/idempotency-gated mutation |
| `resume_connector` | `POST /internal/ops/projects/{projectId}/connectors/{connectorName}/resume` | implemented | approval/idempotency-gated mutation |
| `restart_consumer_group` | `POST /internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/restart` | implemented | approval/idempotency-gated mutation |

Health/metadata endpoints(`health`, `ready`, `version`)도 `/internal/ops`에 구현되어 있지만 runtime tool catalog의 Agent read operation에는 포함되지 않는다.

## `list_alerts`

`list_alerts`는 별도 alert 테이블이 아니라 Spring incident row를 agent alert view로 투영한다.

| 항목 | 구현 |
| --- | --- |
| project lookup | `list_alerts`는 `{projectId}`가 UUID면 workspace id로 조회하고, 실패하거나 UUID가 아니면 namespace slug로 조회. 다른 Spring internal ops pipeline/mutation handler는 현재 namespace slug 조회다 |
| query | `status`, `severity`, `limit` |
| limit | 기본 50, 내부 최대 200. 200 초과 값은 400이 아니라 200으로 cap된다 |
| item fields | `alert_id`, `severity`, `status`, `summary`, `labels`, `occurred_at`, `incident_id` |
| project 없음 | HTTP 404 + `OpsEnvelope.error(code=RESOURCE_NOT_FOUND)` |
| limit 형식/범위 오류 | non-integer 또는 `<= 0`이면 HTTP 400 + `OpsEnvelope.error(code=VALIDATION_FAILED)` |

## 구현 범위 밖

FastAPI tool registry에는 Agent 설계용 논리 tool alias도 포함된다. Spring `tool-catalog`는 runtime에서 실제 호출 가능한 operation 이름을 반환하므로 FastAPI의 `get_metrics`/`get_deployments`는 각각 Spring `query_metrics`/`get_recent_changes`에 대응하고, `get_kafka_lag`는 `get_consumer_lag`의 FastAPI alias다. 실행 가능한 Spring mutation subset은 [Spring Boot API §6.4](./springboot.md#64-mutation-endpoints)에 별도로 문서화되어 있다.
