"""Tool Client Registry and Spring /internal/ops schemas.

Mirrors docs/design/backend-fastapi/tool-catalog.md and
docs/api/internal-ops-read-tools.md.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.state import RiskLevel


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


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
        return headers

    def with_idempotency_key(self, key: str) -> "ToolContext":
        return self.model_copy(update={"idempotency_key": key})


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
    result: dict[str, Any] | None = None
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


class ProjectPipelineSummary(StrictModel):
    pipeline_id: str
    name: str
    pattern: str
    source_db_id: str
    sink_db_id: str | None = None
    status: str
    updated_at: datetime | None = None


class ListProjectPipelinesData(StrictModel):
    pipelines: list[ProjectPipelineSummary] = Field(default_factory=list)


class PipelineDependencyRef(StrictModel):
    db_id: str
    alias: str


class PipelineConnectorRef(StrictModel):
    cr_name: str
    kind: str
    state: str


class PipelineTopologyData(StrictModel):
    pipeline_id: str
    pattern: str
    source: PipelineDependencyRef
    sink: PipelineDependencyRef | None = None
    connectors: list[PipelineConnectorRef] = Field(default_factory=list)
    topics: list[str] = Field(default_factory=list)
    status: str


class ConnectorTaskStatus(StrictModel):
    task_id: int
    state: str
    worker_id: str | None = None


class ConnectorStatusData(StrictModel):
    connector_name: str
    state: str
    tasks: list[ConnectorTaskStatus] = Field(default_factory=list)
    last_error: str | None = None
    observed_at: datetime | None = None


class ConsumerLagPartition(StrictModel):
    topic: str
    partition: int
    current_offset: int
    log_end_offset: int
    lag: int


class ConsumerLagData(StrictModel):
    consumer_group: str
    total_lag: int
    partitions: list[ConsumerLagPartition] = Field(default_factory=list)
    observed_at: datetime | None = None


class TimeRange(StrictModel):
    from_: str = Field(alias="from")
    to: str


class LogSearchRequest(StrictModel):
    query: str
    time_range: TimeRange
    pipeline_id: str | None = None
    limit: int | None = None


class LogSearchData(StrictModel):
    match_count: int
    summary: str


class TriggerEventSummary(StrictModel):
    event_id: str
    level: str
    occurred_at: datetime


class IncidentSummaryData(StrictModel):
    incident_id: str
    severity: str
    status: str
    trigger_event: TriggerEventSummary
    related_event_count: int
    grouping_key: str
    affected_rows_estimate: int | None = None
    root_cause_summary: str | None = None


# ── catalog §8.1 Observability ────────────────────────────────────────────────

class MetricsDataPoint(StrictModel):
    timestamp: str
    value: float


class MetricsData(StrictModel):
    metric: str
    summary: str
    data_points: list[MetricsDataPoint] = Field(default_factory=list)


class GetTracesParams(StrictModel):
    connector_name: str
    limit: int | None = None


class TraceEntry(StrictModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    task_id: int | None = Field(default=None, alias="taskId")
    state: str | None = None
    trace: str


class TracesData(StrictModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    connector_name: str | None = Field(default=None, alias="connector")
    traces: list[TraceEntry] = Field(default_factory=list)
    summary: str | None = None
    note: str | None = None


class GetAlertsParams(StrictModel):
    status: str | None = None
    severity: str | None = None
    limit: int | None = None


class AlertSummaryData(StrictModel):
    alert_id: str
    severity: str
    status: str
    summary: str
    labels: dict[str, str] = Field(default_factory=dict)
    occurred_at: datetime | None = None
    incident_id: str | None = None


class AlertsData(StrictModel):
    alerts: list[AlertSummaryData] = Field(default_factory=list)
    summary: str | None = None


# ── catalog §8.2 Pipeline / Change ───────────────────────────────────────────

class DeploymentChangeSummary(StrictModel):
    change_id: str
    type: str
    description: str
    changed_at: datetime | None = None


class DeploymentsData(StrictModel):
    changes: list[DeploymentChangeSummary] = Field(default_factory=list)


# ── catalog §8.6 Mutation (write) actions ─────────────────────────────────────

class ConnectorActionData(StrictModel):
    connector_name: str
    action: str
    status: str
    message: str | None = None


class ConsumerGroupActionData(StrictModel):
    consumer_group: str
    action: str
    status: str
    message: str | None = None
