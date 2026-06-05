"""SSE progress event schemas for the Agent UI.

Mirrors docs/design/backend-fastapi/contract/contract-streaming-events.md.
Payloads must not contain raw logs, secrets, prompts, or hidden reasoning.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class StreamingEventType(str, Enum):
    RUN_STARTED = "run_started"
    AGENT_STARTED = "agent_started"
    AGENT_COMPLETED = "agent_completed"
    TOOL_CALL_STARTED = "tool_call_started"
    TOOL_CALL_COMPLETED = "tool_call_completed"
    TOOL_CALL_FAILED = "tool_call_failed"
    EVIDENCE_COLLECTED = "evidence_collected"
    REPORT_PREVIEW_AVAILABLE = "report_preview_available"
    PARTIAL_RESULT = "partial_result"
    APPROVAL_REQUIRED = "approval_required"
    CHANGE_MANAGEMENT_REQUIRED = "change_management_required"
    EXECUTION_STARTED = "execution_started"
    EXECUTION_COMPLETED = "execution_completed"
    VERIFICATION_COMPLETED = "verification_completed"
    RUN_COMPLETED = "run_completed"
    DEBUG_TRACE = "debug_trace"


class StreamingEvent(StrictModel):
    event_id: str
    run_id: str
    timestamp: datetime
    type: StreamingEventType
    agent: str | None = None
    message: str
    payload: dict[str, Any] = Field(default_factory=dict)


class ToolCallPayload(StrictModel):
    tool: str
    step_id: str | None = None
    status: str | None = None
    evidence_id: str | None = None
    summary: str | None = None


class EvidenceCollectedPayload(StrictModel):
    evidence_id: str
    evidence_type: str
    summary: str
    redaction_status: str


class ReportPreviewPayload(StrictModel):
    root_cause_id: str | None = None
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    verified: bool = False


class ApprovalRequiredPayload(StrictModel):
    action_id: str
    approval_id: str | None = None
    reason: str


class ChangeManagementRequiredPayload(StrictModel):
    action_id: str
    reason: str
    required_fields: list[str] = Field(default_factory=list)


class ExecutionPayload(StrictModel):
    action_id: str
    tool_name: str
    status: str
    audit_event_id: str | None = None
    summary: str | None = None


class VerificationPayload(StrictModel):
    verification_id: str
    target: str
    status: str
    approved_for_final_response: bool
    reason: str


class EventHistory(StrictModel):
    run_id: str
    events: list[StreamingEvent] = Field(default_factory=list)
    next_cursor: int | None = None
