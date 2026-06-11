"""Tool Client Registry and Spring /internal/ops schemas.

Mirrors docs/design/backend-fastapi/tool-catalog.md and
docs/api/internal-ops-read-tools.md.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from app.schemas.state import RiskLevel


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SpringResponseModel(BaseModel):
    """Spring /internal/ops/* 응답 매핑 전용 base.

    - alias_generator=to_camel: snake_case 필드가 camelCase alias 자동 부여
    - populate_by_name=True: snake_case·camelCase 양쪽 수용
    - extra="ignore": Spring 의 무관 필드 (예: ConsumerLagResult.source) 거부 안 함

    StrictModel (request/internal schema) 는 그대로 유지 — extra="forbid" 보호 정책 보존.
    """
    model_config = ConfigDict(
        populate_by_name=True,
        alias_generator=to_camel,
        extra="ignore",
    )


class ToolStatus(str, Enum):
    SUCCESS = "success"
    FAILED = "failed"
    BLOCKED = "blocked"
    TIMEOUT = "timeout"


class SpringErrorCode(str, Enum):
    VALIDATION_FAILED = "VALIDATION_FAILED"
    POLICY_DENIED = "POLICY_DENIED"
    APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
    APPROVAL_EXPIRED = "APPROVAL_EXPIRED"
    APPROVAL_SCOPE_MISMATCH = "APPROVAL_SCOPE_MISMATCH"
    CHANGE_TICKET_REQUIRED = "CHANGE_TICKET_REQUIRED"
    CHANGE_WINDOW_CLOSED = "CHANGE_WINDOW_CLOSED"
    CHANGE_SCOPE_MISMATCH = "CHANGE_SCOPE_MISMATCH"
    RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND"
    RESOURCE_NOT_OWNED_BY_PROJECT = "RESOURCE_NOT_OWNED_BY_PROJECT"
    PIPELINE_NOT_FOUND = "PIPELINE_NOT_FOUND"
    CONNECTOR_NOT_FOUND = "CONNECTOR_NOT_FOUND"
    CONSUMER_GROUP_NOT_FOUND = "CONSUMER_GROUP_NOT_FOUND"
    INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"
    CONFLICT = "CONFLICT"
    IDEMPOTENCY_REPLAY = "IDEMPOTENCY_REPLAY"
    TIMEOUT = "TIMEOUT"
    TRANSIENT_ERROR = "TRANSIENT_ERROR"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    UNAUTHENTICATED = "UNAUTHENTICATED"
    UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class ToolContext(StrictModel):
    run_id: str
    step_id: str
    agent_name: str
    project_id: str
    request_id: str
    user_id: str | None = None
    incident_id: str | None = None
    pipeline_id: str | None = None
    idempotency_key: str | None = None
    # HITL governance: 승인된 mutation 실행 시 Spring governance gate(approval/change)에
    # context를 전달하기 위한 식별자. read tool 은 미설정이라 자연히 헤더 미전송. (#475)
    approval_id: str | None = None
    change_ticket_id: str | None = None

    def spring_headers(self, actor_id: str = "bifrost-agent") -> dict[str, str]:
        headers: dict[str, str] = {
            "X-Agent-Run-Id": self.run_id,
            "X-Agent-Step-Id": self.step_id,
            "X-Agent-Name": self.agent_name,
            "X-Request-Id": self.request_id,
            "X-Actor-Type": "agent",
            "X-Actor-Id": actor_id,
        }
        if self.idempotency_key:
            headers["X-Idempotency-Key"] = self.idempotency_key
        # 값이 있을 때만 emit — Spring InternalOpsMutationController 가 X-Approval-Id /
        # X-Change-Ticket-Id 로 approval/change governance gate 를 검증한다. (#475)
        if self.approval_id:
            headers["X-Approval-Id"] = self.approval_id
        if self.change_ticket_id:
            headers["X-Change-Ticket-Id"] = self.change_ticket_id
        return headers

    def with_idempotency_key(self, key: str) -> "ToolContext":
        return self.model_copy(update={"idempotency_key": key})

    def with_approval(
        self,
        approval_id: str | None = None,
        change_ticket_id: str | None = None,
    ) -> "ToolContext":
        """승인된 action 실행용 governance 식별자를 실은 사본을 반환. (#475)"""
        update: dict[str, str] = {}
        if approval_id:
            update["approval_id"] = approval_id
        if change_ticket_id:
            update["change_ticket_id"] = change_ticket_id
        return self.model_copy(update=update) if update else self


class ToolCallRequest(StrictModel):
    tool_name: str
    params: dict[str, Any] = Field(default_factory=dict)
    context: ToolContext

    def spring_headers(self, actor_id: str = "bifrost-agent") -> dict[str, str]:
        return self.context.spring_headers(actor_id=actor_id)


class ReadToolRequest(ToolCallRequest):
    idempotency_key: None = None


class MutationToolRequest(ToolCallRequest):
    idempotency_key: str

    def spring_headers(self, actor_id: str = "bifrost-agent") -> dict[str, str]:
        headers = super().spring_headers(actor_id=actor_id)
        headers["X-Idempotency-Key"] = self.idempotency_key
        return headers


class EvidenceRef(StrictModel):
    evidence_id: str
    store_ref: str
    summary: str
    redaction_status: str
    type: str | None = None


class ToolError(StrictModel):
    code: SpringErrorCode | str
    message: str
    retryable: bool = False
    required_action: str | None = None


class SpringOpsResponse(StrictModel):
    ok: bool
    request_id: str
    operation: str
    # Spring 가 dict 또는 raw list (예: list_project_pipelines) 모두 반환 가능.
    # raw list 응답은 spring_client.py 의 LIST_RESULT_WRAPPER 가 wrapping 처리.
    result: dict[str, Any] | list[Any] | None = None
    evidence: list[EvidenceRef] = Field(default_factory=list)
    audit_event_id: str | None = None
    error: ToolError | None = None


class ToolResult(StrictModel):
    tool_name: str
    status: ToolStatus
    risk: RiskLevel
    requires_approval: bool = False
    summary: str
    evidence_ids: list[str] = Field(default_factory=list)
    audit_event_id: str | None = None
    error: ToolError | None = None


class ProjectPipelineSummary(SpringResponseModel):
    """Spring PipelineResponse{id, name, pattern, status, sourceDbId, sinkDbId, createdAt, ...} 수용.

    Spring 은 식별자를 "id"(UUID) 로 반환 — ai-service schema 의 pipeline_id 로 normalize (#474).
    createdAt 만 제공하고 updatedAt 은 미제공 → updated_at 은 createdAt fallback / 없으면 None.
    """
    pipeline_id: str
    name: str
    pattern: str
    source_db_id: str
    sink_db_id: str | None = None
    status: str
    updated_at: datetime | None = None

    @model_validator(mode="before")
    @classmethod
    def _normalize_spring_pipeline(cls, data: Any) -> Any:
        if isinstance(data, dict):
            data = dict(data)
            if "pipeline_id" not in data and "pipelineId" not in data and "id" in data:
                data["pipeline_id"] = data["id"]
            if "updated_at" not in data and "updatedAt" not in data and "createdAt" in data:
                data["updated_at"] = data["createdAt"]
        return data


class ListProjectPipelinesData(SpringResponseModel):
    pipelines: list[ProjectPipelineSummary] = Field(default_factory=list)


class PipelineDependencyRef(SpringResponseModel):
    db_id: str
    # Spring PipelineTopologyResult 는 datasource alias 를 제공하지 않음 (sourceDbId/sinkDbId UUID 만) — optional 완화 (#474).
    alias: str | None = None


class PipelineConnectorRef(SpringResponseModel):
    # Spring ConnectorResponse 는 커넥터 식별자를 "name" 으로 반환 — alias_generator(to_camel) 의 "crName" 과 다르므로 명시 alias (#474).
    cr_name: str = Field(alias="name")
    kind: str
    state: str | None = None


class PipelineTopologyData(SpringResponseModel):
    """Spring PipelineTopologyResult{pipelineId, pattern, status, topic, sourceDbId, sinkDbId,
    sourceConnector, sinkConnector, connectors[]} 의 flat 형태를 nested schema 로 normalize (#474).

    - source/sink: flat sourceDbId/sinkDbId → PipelineDependencyRef{db_id} 로 구성
    - topics: 단일 topic 문자열 → 리스트로 wrap
    - connectors: ConnectorResponse(name/kind/state) → PipelineConnectorRef(cr_name 명시 alias)
    """
    pipeline_id: str
    pattern: str
    source: PipelineDependencyRef | None = None
    sink: PipelineDependencyRef | None = None
    connectors: list[PipelineConnectorRef] = Field(default_factory=list)
    topics: list[str] = Field(default_factory=list)
    status: str

    @model_validator(mode="before")
    @classmethod
    def _normalize_spring_topology(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data
        data = dict(data)
        if "source" not in data:
            source_db = data.get("sourceDbId") or data.get("source_db_id")
            if source_db is not None:
                data["source"] = {"db_id": source_db}
        if "sink" not in data:
            sink_db = data.get("sinkDbId") or data.get("sink_db_id")
            if sink_db is not None:
                data["sink"] = {"db_id": sink_db}
        if "topics" not in data:
            topic = data.get("topic")
            if isinstance(topic, str) and topic:
                data["topics"] = [topic]
        return data


class ConnectorTaskStatus(SpringResponseModel):
    # Spring PipelineProvisionStatus 의 task 는 "id" 필드로 반환 — alias_generator(to_camel) 의 "taskId" 와 다르므로 명시 alias 필수 (#448).
    task_id: int = Field(alias="id")
    state: str
    worker_id: str | None = None


class ConnectorStatusData(SpringResponseModel):
    connector_name: str
    # Spring PipelineProvisionStatus 는 "connectorState" 로 반환 — alias_generator(to_camel) 의 "state" 와 다르므로 명시 alias 필수 (#448).
    state: str = Field(alias="connectorState")
    tasks: list[ConnectorTaskStatus] = Field(default_factory=list)
    last_error: str | None = None
    observed_at: datetime | None = None


class ConsumerLagPartition(SpringResponseModel):
    topic: str
    partition: int
    current_offset: int
    log_end_offset: int
    lag: int


class ConsumerLagData(SpringResponseModel):
    consumer_group: str
    total_lag: int
    partitions: list[ConsumerLagPartition] = Field(default_factory=list)
    observed_at: datetime | None = None
    source: str | None = None  # Spring ConsumerLagResult.source (kafka-admin 등) — RCA evidence 메모용


class TimeRange(StrictModel):
    from_: str = Field(alias="from")
    to: str


class LogSearchRequest(StrictModel):
    query: str
    time_range: TimeRange
    pipeline_id: str | None = None
    limit: int | None = None


class LogSearchData(SpringResponseModel):
    """Spring LogSearchResult{logs, total, note} 및 ai-service 의도된 {match_count, summary} 양쪽 수용.

    Spring 가 반환한 total/note 를 model_validator 가 match_count/summary 로 normalize.
    """
    match_count: int = 0
    summary: str | None = None
    logs: list[dict[str, Any]] = Field(default_factory=list)
    total: int | None = None
    note: str | None = None

    @model_validator(mode="before")
    @classmethod
    def _normalize_spring_logsearch(cls, data: Any) -> Any:
        if isinstance(data, dict):
            data = dict(data)
            if "match_count" not in data and "matchCount" not in data and "total" in data:
                data["match_count"] = data["total"]
            if "summary" not in data and "note" in data:
                data["summary"] = data["note"]
        return data


class TriggerEventSummary(SpringResponseModel):
    event_id: str
    level: str
    occurred_at: datetime


class IncidentSummaryData(SpringResponseModel):
    """Spring IncidentSummaryResult{incidentId, status, note} 수용 (#474).

    Spring 의 incident read model 은 현재 severity/trigger_event/related_event_count/grouping_key 를
    제공하지 않으므로 해당 필드를 optional 로 완화한다. note 는 summary 로 normalize.
    """
    incident_id: str
    status: str
    severity: str | None = None
    trigger_event: TriggerEventSummary | None = None
    related_event_count: int | None = None
    grouping_key: str | None = None
    note: str | None = None
    summary: str | None = None
    affected_rows_estimate: int | None = None
    root_cause_summary: str | None = None

    @model_validator(mode="before")
    @classmethod
    def _normalize_spring_incident(cls, data: Any) -> Any:
        if isinstance(data, dict):
            data = dict(data)
            if "summary" not in data and "note" in data:
                data["summary"] = data["note"]
        return data


# ── catalog §8.1 Observability ────────────────────────────────────────────────

class MetricsDataPoint(SpringResponseModel):
    timestamp: str
    value: float


class MetricsData(SpringResponseModel):
    metric: str
    summary: str
    data_points: list[MetricsDataPoint] = Field(default_factory=list)


class GetTracesParams(StrictModel):
    connector_name: str
    limit: int | None = None


class GetConnectorTaskTraceParams(StrictModel):
    connector_name: str


class TraceSpan(SpringResponseModel):
    """Tempo span 요약 (#373). 무엇이(name) 어느 서비스에서(service) 얼마나(duration_ms) 걸렸고 실패(status/error)했나."""
    name: str | None = None
    service: str | None = None
    duration_ms: int | None = None
    status: str | None = None
    error: str | None = None


class TracesData(SpringResponseModel):
    """get_traces — Tempo 분산 trace summary (#373). 변경 이벤트가 source→topic→sink로 흐르며 어디서 지연/실패했나.

    Spring TraceSummaryResult 와 정합 (to_camel alias 가 camelCase wire 수용). 비활성/미발견/실패 시
    Spring 이 stub(trace_id=null, status="unknown", spans=[])을 반환하므로 모든 필드 optional.
    """
    trace_id: str | None = None
    pipeline_id: str | None = None
    status: str | None = None
    duration_ms: int | None = None
    spans: list[TraceSpan] = Field(default_factory=list)
    note: str | None = None


class TraceEntry(SpringResponseModel):
    # 명시 alias "taskId" 보존 (alias_generator(to_camel) 가 동일하게 생성하지만 명시성 유지).
    task_id: int | None = Field(default=None, alias="taskId")
    state: str | None = None
    trace: str | None = None


class ConnectorTaskTraceData(SpringResponseModel):
    """get_connector_task_trace — Kafka Connect task 예외 trace (#368/#373). 에러 근거를 분산 trace(get_traces)와 분리."""
    # Spring 가 "connector" 필드명으로 반환 — alias_generator(to_camel) 의 "connectorName" 과 다르므로 명시 alias 필수.
    connector_name: str | None = Field(default=None, alias="connector")
    traces: list[TraceEntry] = Field(default_factory=list)
    summary: str | None = None
    note: str | None = None


class GetAlertsParams(StrictModel):
    status: str | None = None
    severity: str | None = None
    limit: int | None = None


class AlertSummaryData(SpringResponseModel):
    alert_id: str
    severity: str
    status: str
    summary: str
    labels: dict[str, str] = Field(default_factory=dict)
    occurred_at: datetime | None = None
    incident_id: str | None = None


class AlertsData(SpringResponseModel):
    alerts: list[AlertSummaryData] = Field(default_factory=list)
    summary: str | None = None


# ── catalog §8.2 Pipeline / Change ───────────────────────────────────────────

class DeploymentChangeSummary(SpringResponseModel):
    change_id: str
    type: str
    description: str
    changed_at: datetime | None = None


class DeploymentsData(SpringResponseModel):
    changes: list[DeploymentChangeSummary] = Field(default_factory=list)


# ── catalog §8.6 Mutation (write) actions ─────────────────────────────────────

class ConnectorActionData(SpringResponseModel):
    connector_name: str
    action: str
    status: str
    message: str | None = None


class ConsumerGroupActionData(SpringResponseModel):
    consumer_group: str
    action: str
    status: str
    message: str | None = None
