"""Online feedback and drift report API (#890)."""
from __future__ import annotations

import uuid
from typing import Literal

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from app.core.drift import build_drift_report
from app.persistence.online_feedback_repository import OnlineFeedbackEvent
from app.schemas import ApiResponse, ErrorCode

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class OnlineFeedbackRequest(StrictModel):
    action: Literal["accept", "modify", "override"]
    original_root_cause_id: str | None = None
    final_root_cause_id: str | None = None
    original_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    final_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    modified_fields: list[str] = Field(default_factory=list)
    override_reason: str | None = None
    operator_id: str | None = None
    metadata: dict[str, object] = Field(default_factory=dict)


@router.post("/runs/{run_id}/online-feedback", response_model=None)
async def add_online_feedback(run_id: str, req: OnlineFeedbackRequest) -> ApiResponse | JSONResponse:
    from app.persistence.online_feedback_repository import get_online_feedback_repo
    from app.persistence.run_repository import get_run_repo

    request_id = _request_id()
    run = await get_run_repo().get(run_id)
    if run is None:
        body = ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"run not found: {run_id}")
        return JSONResponse(status_code=404, content=body.model_dump(mode="json"))

    event_id = await get_online_feedback_repo().create(
        OnlineFeedbackEvent(
            event_id=f"ofb_{uuid.uuid4().hex[:12]}",
            run_id=run_id,
            action=req.action,
            original_root_cause_id=req.original_root_cause_id,
            final_root_cause_id=req.final_root_cause_id,
            original_confidence=req.original_confidence,
            final_confidence=req.final_confidence,
            modified_fields=req.modified_fields,
            override_reason=req.override_reason,
            operator_id=req.operator_id,
            metadata=req.metadata,
        )
    )
    return ApiResponse.success(request_id, {"event_id": event_id})


@router.get("/drift-report")
async def drift_report(limit: int = 500) -> ApiResponse:
    from app.persistence.online_feedback_repository import get_online_feedback_repo

    events = await get_online_feedback_repo().list_recent(limit=limit)
    return ApiResponse.success(_request_id(), build_drift_report(events))
