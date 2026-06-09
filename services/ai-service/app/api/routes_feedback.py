"""Feedback and audit event API (design fastapi/api.md §16)."""
from __future__ import annotations

import inspect
import uuid
from typing import Literal

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from app.schemas import ApiResponse, ErrorCode

router = APIRouter()

AUDIT_EVENT_TYPES = {
    "execution_started",
    "execution_completed",
    "approval_required",
    "change_management_required",
    "verification_completed",
    "run_completed",
}


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class FeedbackRequest(StrictModel):
    rating: int = Field(ge=1, le=5)
    category: Literal[
        "rca_quality",
        "evidence_completeness",
        "action_relevance",
        "report_clarity",
    ]
    comment: str | None = None
    submitted_by: str | None = None


@router.post("/runs/{run_id}/feedback", response_model=None)
async def add_feedback(run_id: str, req: FeedbackRequest) -> ApiResponse | JSONResponse:
    from app.persistence.feedback_repository import get_feedback_repo
    from app.persistence.run_repository import get_run_repo

    request_id = _request_id()
    run = await get_run_repo().get(run_id)
    if run is None:
        body = ApiResponse.failure(
            request_id,
            ErrorCode.RUN_NOT_FOUND,
            f"run not found: {run_id}",
        )
        return JSONResponse(status_code=404, content=body.model_dump(mode="json"))

    feedback_id = await get_feedback_repo().create(
        run_id=run_id,
        rating=req.rating,
        category=req.category,
        comment=req.comment,
        submitted_by=req.submitted_by,
    )
    return ApiResponse.success(request_id, {"feedback_id": feedback_id})


@router.get("/runs/{run_id}/audit-events")
async def list_audit_events(run_id: str) -> ApiResponse:
    from app.persistence.event_repository import get_event_repo

    events_result = get_event_repo().get_after(run_id, last_event_id=None)
    events = await events_result if inspect.isawaitable(events_result) else events_result
    items = [
        {
            "event_id": event.event_id,
            "type": _event_type(event),
            "agent": event.agent,
            "message": event.message,
            "created_at": event.timestamp.isoformat(),
        }
        for event in events
        if _event_type(event) in AUDIT_EVENT_TYPES
    ]
    return ApiResponse.success(_request_id(), {"items": items})


@router.get("/audit-events/{audit_event_id}")
async def get_audit_event(audit_event_id: str) -> ApiResponse:
    return ApiResponse.failure(
        _request_id(),
        ErrorCode.NOT_IMPLEMENTED,
        "audit event detail is Spring SoT (v1)",
    )


def _event_type(event) -> str:
    event_type = event.type
    return event_type.value if hasattr(event_type, "value") else str(event_type)
