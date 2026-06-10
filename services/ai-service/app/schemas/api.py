"""DTOs for REST API request/response fragments."""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MessageRequest(StrictModel):
    message: str
    stream: bool = True


class RetryRequest(StrictModel):
    from_stage: str | None = None


class StateNamespaceSummary(StrictModel):
    patch_count: int
    last_author: str | None = None
    last_op: str | None = None
    last_updated_at: datetime | None = None


class StepSummary(StrictModel):
    step_id: str
    agent: str
    status: str
    created_at: datetime | None = None


class TimelineItem(StrictModel):
    seq: int | None = None
    type: str
    agent: str | None = None
    message: str
    created_at: datetime


class ActionSummary(StrictModel):
    action_id: str
    action_type: str
    tool_name: str | None = None
    risk: str
    policy_decision: str | None = None
    approval_id: str | None = None
    approval_status: str | None = None
    execution_status: str | None = None
    audit_event_id: str | None = None
