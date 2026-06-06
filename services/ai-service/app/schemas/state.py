"""Agent workflow State models.

Mirrors docs/design/backend-fastapi/contract/contract-state-schema.md.
State stores metadata and references only. Raw logs, secrets, prompts, and
connection strings must stay outside these models.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class AgentMode(str, Enum):
    SIMPLE_QUERY = "simple_query"
    INCIDENT_ANALYSIS = "incident_analysis"
    ACTION_EXECUTION = "action_execution"
    APPROVAL_DECISION = "approval_decision"


class RunStatus(str, Enum):
    RUNNING = "running"
    WAITING_FOR_APPROVAL = "waiting_for_approval"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class StateNamespace(str, Enum):
    RUN = "run"
    INCIDENT = "incident"
    CORRELATION = "correlation"
    EVIDENCE = "evidence"
    ANALYSIS = "analysis"
    ACTIONS = "actions"
    VERIFICATION = "verification"
    REPORT = "report"


class PatchOperation(str, Enum):
    APPEND = "append"
    VERSION = "version"
    TOMBSTONE = "tombstone"


class EvidenceType(str, Enum):
    PIPELINE_LOG = "pipeline_log"
    METRIC = "metric"
    TRACE = "trace"
    EVENT = "event"
    SNAPSHOT = "snapshot"
    KNOWLEDGE = "knowledge"
    TOOL_RESULT = "tool_result"


class RedactionStatus(str, Enum):
    REDACTED = "redacted"
    TOMBSTONED = "tombstoned"


class IncidentScope(str, Enum):
    SINGLE = "single"
    INCIDENT_GROUP = "incident_group"


class Severity(str, Enum):
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"


class ActionType(str, Enum):
    RUNTIME_TOOL = "runtime_tool"
    WORKFLOW_ACTION = "workflow_action"
    COMPOSITE_ACTION = "composite_action"
    NOTIFICATION = "notification"
    ESCALATION = "escalation"


class RiskLevel(str, Enum):
    READ_ONLY = "read_only"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    FORBIDDEN = "forbidden"


class PolicyDecisionType(str, Enum):
    ALLOW = "allow"
    REQUIRE_APPROVAL = "require_approval"
    REQUIRE_CHANGE_MANAGEMENT = "require_change_management"
    DENY = "deny"


class ActionStatus(str, Enum):
    PENDING_APPROVAL = "pending_approval"
    READY = "ready"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    BLOCKED = "blocked"


class VerificationStatus(str, Enum):
    PASS = "pass"
    FAIL = "fail"
    NEEDS_REVISION = "needs_revision"


class RunGuards(StrictModel):
    revision_counts: dict[str, int] = Field(default_factory=dict)
    gap_loops: int = 0
    scope_loops: int = 0
    revise_action_loops: int = 0
    fail_loops: int = 0


class RunPlanState(StrictModel):
    executed_plan_hashes: list[str] = Field(default_factory=list)


class RunState(StrictModel):
    run_id: str
    mode: AgentMode = AgentMode.SIMPLE_QUERY
    status: RunStatus = RunStatus.RUNNING
    current_agent: str | None = None
    retry_count: int = 0
    step_count: int = 0
    guards: RunGuards = Field(default_factory=RunGuards)
    plan: RunPlanState = Field(default_factory=RunPlanState)


class IncidentState(StrictModel):
    incident_id: str | None = None
    scope: IncidentScope | None = None
    severity: Severity | None = None
    user_message: str | None = None


class CorrelationState(StrictModel):
    correlation_id: str | None = None
    related_alert_ids: list[str] = Field(default_factory=list)


class EvidenceItem(StrictModel):
    evidence_id: str
    type: EvidenceType | str
    store_ref: str
    summary: str
    redaction_status: RedactionStatus = RedactionStatus.REDACTED
    collected_by: str | None = None
    collected_at: datetime | None = None


class EvidenceState(StrictModel):
    items: list[EvidenceItem] = Field(default_factory=list)


class IncidentTypeCandidate(StrictModel):
    type: str
    confidence: float = Field(ge=0.0, le=1.0)
    evidence_ids: list[str] = Field(default_factory=list)


class RootCauseCandidate(StrictModel):
    root_cause_id: str
    confidence: float = Field(ge=0.0, le=1.0)
    required_evidence_satisfied: bool
    supporting_evidence_ids: list[str] = Field(default_factory=list)
    negative_evidence_ids: list[str] = Field(default_factory=list)
    evidence_gap: list[str] = Field(default_factory=list)
    explanation: str


class AnalysisState(StrictModel):
    incident_types: list[IncidentTypeCandidate] = Field(default_factory=list)
    root_cause_candidates: list[RootCauseCandidate] = Field(default_factory=list)


class ActionCandidate(StrictModel):
    action_id: str
    action_type: ActionType
    action_name: str
    root_cause_id: str | None = None
    risk: RiskLevel
    reason: str
    expected_effect: str | None = None
    rollback_plan: str | None = None
    estimated_duration: str | None = None
    tool_name: str | None = None
    status: ActionStatus | None = None

    @model_validator(mode="after")
    def runtime_tool_requires_tool_name(self) -> "ActionCandidate":
        if self.action_type == ActionType.RUNTIME_TOOL and not self.tool_name:
            raise ValueError("tool_name is required for runtime_tool actions")
        return self


class PolicyDecision(StrictModel):
    action_id: str
    action_type: ActionType
    risk: RiskLevel
    decision: PolicyDecisionType
    status: ActionStatus
    reason: str
    tool_name: str | None = None
    required_approver: str | None = None


class ApprovalRequestState(StrictModel):
    approval_id: str
    action_id: str
    params_hash: str
    status: str


class ApprovedActionState(StrictModel):
    approval_id: str
    action_id: str
    params_hash: str


class ChangeManagementRecord(StrictModel):
    change_ticket_id: str
    action_id: str
    status: str


class ExecutionResult(StrictModel):
    action_id: str
    tool_name: str
    status: ActionStatus
    audit_event_id: str | None = None
    before_evidence_id: str | None = None
    after_evidence_id: str | None = None
    reason_code: str | None = None
    summary: str


class ActionsState(StrictModel):
    candidates: list[ActionCandidate] = Field(default_factory=list)
    policy_decisions: list[PolicyDecision] = Field(default_factory=list)
    approval_requests: list[ApprovalRequestState] = Field(default_factory=list)
    approved_actions: list[ApprovedActionState] = Field(default_factory=list)
    change_management_records: list[ChangeManagementRecord] = Field(default_factory=list)
    execution_results: list[ExecutionResult] = Field(default_factory=list)


class VerificationResult(StrictModel):
    verification_id: str
    status: VerificationStatus
    target: str
    reason: str
    approved_for_final_response: bool
    next_agent: str | None = None


class VerificationState(StrictModel):
    verification_results: list[VerificationResult] = Field(default_factory=list)


class FinalEvidenceSummary(StrictModel):
    evidence_id: str
    summary: str


class FinalActionSummary(StrictModel):
    action_id: str
    risk: RiskLevel
    estimated_duration: str | None = None
    status: ActionStatus


class FinalResponse(StrictModel):
    incident_id: str
    summary: str
    root_cause_id: str
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: list[FinalEvidenceSummary] = Field(default_factory=list)
    actions: list[FinalActionSummary] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)


class ReportState(StrictModel):
    draft: dict[str, Any] | None = None
    final: FinalResponse | None = None


class AgentState(StrictModel):
    run: RunState
    incident: IncidentState = Field(default_factory=IncidentState)
    correlation: CorrelationState = Field(default_factory=CorrelationState)
    evidence: EvidenceState = Field(default_factory=EvidenceState)
    analysis: AnalysisState = Field(default_factory=AnalysisState)
    actions: ActionsState = Field(default_factory=ActionsState)
    verification: VerificationState = Field(default_factory=VerificationState)
    report: ReportState = Field(default_factory=ReportState)


class StatePatch(StrictModel):
    patch_id: str | None = None
    run_id: str
    agent: str
    namespace: StateNamespace
    operation: PatchOperation
    path: str
    value_ref: str | None = None
    created_at: datetime
