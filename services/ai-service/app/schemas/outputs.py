"""Structured LLM and deterministic stage output schemas.

Mirrors docs/design/backend-fastapi/contract/contract-output-schemas.md.
These models describe stage outputs before they are persisted as State patches.
"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.state import (
    ActionStatus,
    ActionType,
    AgentMode,
    EvidenceItem,
    FinalResponse,
    IncidentScope,
    PolicyDecisionType,
    RiskLevel,
    RollbackStatus,
    RootCauseCandidate,
    VerificationStatus,
)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class AlertGroup(StrictModel):
    group_id: str
    alert_ids: list[str] = Field(default_factory=list)
    common_labels: dict[str, str] = Field(default_factory=dict)


class CorrelationOutput(StrictModel):
    correlation_id: str
    scope: IncidentScope
    groups: list[AlertGroup] = Field(default_factory=list)
    related_alert_ids: list[str] = Field(default_factory=list)


class RouteDecision(StrictModel):
    mode: AgentMode
    remediation_requested: bool = False
    reuse_existing_analysis: bool = False
    reason: str
    required_flow: list[str]


class RouterOutput(StrictModel):
    route_decision: RouteDecision


class RetrievalPlanStep(StrictModel):
    step_id: str
    tool_name: str
    params: dict[str, Any]
    purpose: str
    required: bool = True
    depends_on: list[str]
    plan_hash: str


class PlannerOutput(StrictModel):
    retrieval_plan: list[RetrievalPlanStep]
    clarification_message: str | None = None


class RetrievalOutput(StrictModel):
    evidence_items: list[EvidenceItem]
    # (#633) ReAct 루프가 전체 도구 결과로 합성한 답변. simple_query 최종 답으로 우선 사용한다
    # (report 단계는 evidence 요약만 봐서 sql_read 행값 등 실데이터를 못 살리기 때문).
    answer: str | None = None


class IncidentTypeOutput(StrictModel):
    type: str
    confidence: float = Field(ge=0.0, le=1.0)
    evidence_ids: list[str] = Field(default_factory=list)


class Classification(StrictModel):
    incident_scope: IncidentScope
    incident_types: list[IncidentTypeOutput]
    needs_incident_group_analysis: bool = False


class ClassifierOutput(StrictModel):
    classification: Classification


class RcaOutput(StrictModel):
    root_cause_candidates: list[RootCauseCandidate]


class ActionCandidateOutput(StrictModel):
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
    tool_params: dict[str, Any] | None = None

    @model_validator(mode="after")
    def runtime_tool_requires_tool_name(self) -> "ActionCandidateOutput":
        if self.action_type == ActionType.RUNTIME_TOOL and not self.tool_name:
            raise ValueError("tool_name is required for runtime_tool actions")
        return self


class RemediationOutput(StrictModel):
    action_candidates: list[ActionCandidateOutput]


class PolicyDecisionOutput(StrictModel):
    action_id: str
    action_type: ActionType
    risk: RiskLevel
    decision: PolicyDecisionType
    status: ActionStatus
    reason: str
    tool_name: str | None = None
    tool_params: dict[str, Any] | None = None
    required_approver: str | None = None

    @model_validator(mode="after")
    def status_matches_policy_decision(self) -> "PolicyDecisionOutput":
        expected_status = {
            PolicyDecisionType.ALLOW: ActionStatus.READY,
            PolicyDecisionType.REQUIRE_APPROVAL: ActionStatus.PENDING_APPROVAL,
            PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT: ActionStatus.PENDING_APPROVAL,
            PolicyDecisionType.DENY: ActionStatus.BLOCKED,
        }[self.decision]
        if self.status != expected_status:
            raise ValueError("status must match policy decision contract")
        return self


class PolicyGuardOutput(StrictModel):
    policy_decisions: list[PolicyDecisionOutput]


class ExecutionResultOutput(StrictModel):
    action_id: str
    tool_name: str
    status: ActionStatus
    audit_event_id: str | None = None
    before_evidence_id: str | None = None
    after_evidence_id: str | None = None
    reason_code: str | None = None
    summary: str
    # #886 조치 전 상태 스냅샷 — 실패 시 자동 롤백의 기준(이전 상태 복원)으로 쓴다.
    pre_change_snapshot: str | None = None

    @model_validator(mode="after")
    def executor_status_is_terminal(self) -> "ExecutionResultOutput":
        if self.status not in {
            ActionStatus.COMPLETED,
            ActionStatus.FAILED,
            ActionStatus.BLOCKED,
        }:
            raise ValueError("executor output status must be completed, failed, or blocked")
        return self


class ExecutorOutput(StrictModel):
    execution_results: list[ExecutionResultOutput]


class RollbackResultOutput(StrictModel):
    """#886 조치 실패 시 자동 롤백 실행 결과(run record 에 남는다)."""

    rollback_action_id: str
    original_action_id: str
    rollback_status: RollbackStatus
    tool_name: str | None = None
    tool_params: dict[str, Any] | None = None
    rollback_audit_event_id: str | None = None
    pre_change_snapshot: str | None = None
    summary: str


class RollbackOutput(StrictModel):
    rollback_results: list[RollbackResultOutput] = Field(default_factory=list)


class ApprovedActionOutput(StrictModel):
    approval_id: str
    action_id: str
    params_hash: str
    approved_by: str | None = None


class ApprovalRequestOutput(StrictModel):
    approval_id: str
    action_id: str
    params_hash: str
    approval_status: str


class ApprovalGateOutput(StrictModel):
    approved_actions: list[ApprovedActionOutput] = Field(default_factory=list)
    approval_requests: list[ApprovalRequestOutput] = Field(default_factory=list)
    run_status: str


class ChangeManagementRecordOutput(StrictModel):
    change_ticket_id: str
    action_id: str
    status: str


class ChangeManagementOutput(StrictModel):
    change_management_records: list[ChangeManagementRecordOutput] = Field(default_factory=list)
    run_status: str


class VerificationResultOutput(StrictModel):
    verification_id: str
    target: str
    status: VerificationStatus
    approved_for_final_response: bool
    reason: str
    next_agent: str | None = None


class VerifierOutput(StrictModel):
    verification_results: list[VerificationResultOutput]


class ReportOutput(StrictModel):
    final_response: FinalResponse
