# Internal Ops Read Tools (WIP)

> 상태: WIP. 이 문서는 FastAPI 논리 tool과 Spring `/internal/ops/**` read endpoint의 현재 구현 상태를 기록한다. 전체 계약 정본은 Spring controller/DTO와 FastAPI schema이며, schema mismatch는 코드 PR에서 별도 정리한다.

## 구현 상태

| Tool | Spring endpoint | Status | 현재 result | 근거 |
| --- | --- | --- | --- | --- |
| `health` | `GET /internal/ops/health` | implemented | `OpsEnvelope<HealthResult>` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsController.java:40-44` |
| `ready` | `GET /internal/ops/ready` | implemented | `OpsEnvelope<ReadyResult>`, DB 실패 시 HTTP 503 | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsController.java:46-54` |
| `version` | `GET /internal/ops/version` | implemented | `OpsEnvelope<VersionResult>` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsController.java:56-60` |
| `get_connector_status` | `GET /internal/ops/projects/{projectId}/kafka/connectors/{connectorName}/status` | implemented | `OpsEnvelope<PipelineProvisionStatus>` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalController.java:73-80` |
| `list_project_pipelines` | `GET /internal/ops/projects/{projectId}/pipelines` | implemented | `OpsEnvelope<List<PipelineResponse>>` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsPipelineController.java:46-58` |
| `get_pipeline_topology` | `GET /internal/ops/projects/{projectId}/pipelines/{pipelineId}/topology` | implemented | `OpsEnvelope<PipelineTopologyResult>` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsPipelineController.java:61-82` |
| `get_consumer_lag` | `GET /internal/ops/projects/{projectId}/kafka/consumer-groups/{consumerGroup}/lag` | partial | `consumerGroup`, `totalLag`, `source`; Kafka 미연결 시 `totalLag=0`, `source=unavailable` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsObservabilityController.java:47-58`, `services/operations-backend/src/main/java/com/bifrost/ops/internalops/dto/ConsumerLagResult.java:3` |
| `search_logs` | `POST /internal/ops/projects/{projectId}/observability/logs/search` | stub | `LogSearchResult.stub()` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsObservabilityController.java:60-68`, `services/operations-backend/src/main/java/com/bifrost/ops/internalops/dto/LogSearchResult.java:5` |
| `get_incident_summary` | `GET /internal/ops/incidents/{incidentId}/summary` | stub | `IncidentSummaryResult.stub(incidentId)` | `services/operations-backend/src/main/java/com/bifrost/ops/internalops/controller/InternalOpsObservabilityController.java:70-78` |

## Known Mismatches

- Spring `OpsEnvelope` JSON field names are Java record names (`requestId`, `auditEventId`), while FastAPI schema expects snake_case (`request_id`, `audit_event_id`) and forbids extras. Code references: `services/operations-backend/src/main/java/com/bifrost/ops/internalops/dto/OpsEnvelope.java:14`, `services/ai-service/app/schemas/tools.py:118`.
- FastAPI sends `X-Request-Id`, but Spring `AgentHeaders` currently reads `X-Agent-Request-Id`. Code references: `services/ai-service/app/schemas/tools.py:64`, `services/operations-backend/src/main/java/com/bifrost/ops/internalops/AgentHeaders.java:13`.
- `get_consumer_lag`, `search_logs`, `list_project_pipelines` result shapes do not yet match the stricter FastAPI tool DTOs (`services/ai-service/app/schemas/tools.py:149`, `services/ai-service/app/schemas/tools.py:196`, `services/ai-service/app/schemas/tools.py:215`).
