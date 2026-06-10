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

## Read Tool Catalog

`GET /internal/ops/admin/tool-catalog`가 현재 Spring Boot에서 구현된 read operation allowlist를 반환한다. Mutation endpoint는 존재하지만 이 catalog에는 포함되지 않는다.

| Operation | Spring endpoint | Status | Result 요약 |
| --- | --- | --- | --- |
| `get_consumer_lag` | `GET /internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag` | implemented | `consumerGroup`, `totalLag`, `source` |
| `search_logs` | `POST /internal/ops/projects/{projectId}/observability/logs/search` | implemented | `logs`, `total`, `note` |
| `query_traces` | `GET /internal/ops/projects/{projectId}/connectors/{connectorName}/traces` | implemented | `connector`, `traces`, optional `note` |
| `list_alerts` | `GET /internal/ops/projects/{projectId}/observability/alerts` | implemented | `alerts`, `summary` |
| `get_incident_summary` | `GET /internal/ops/incidents/{incidentId}/summary` | implemented | incident summary 또는 fallback stub |
| `list_project_pipelines` | `GET /internal/ops/projects/{projectId}/pipelines` | implemented | `PipelineResponse[]` |
| `get_pipeline_topology` | `GET /internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology` | implemented | pipeline topology |
| `get_connector_status` | `GET /internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status` | implemented | `PipelineProvisionStatus` |

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

FastAPI tool registry에는 Agent 설계용 논리 tool이 더 많다. Spring `tool-catalog`가 반환하지 않는 `get_metrics`, `get_deployments`, `get_kafka_lag`, `restart_connector` 등은 이 read catalog의 현재 항목이 아니다. 실행 가능한 Spring mutation subset은 [Spring Boot API §6.4](./springboot.md#64-mutation-endpoints)에 별도로 문서화되어 있다.
