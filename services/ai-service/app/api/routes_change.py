"""Change Management Gate API — 변경관리 티켓 제출 엔드포인트."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel

from app.schemas import ApiResponse

router = APIRouter()


class ChangeTicketRequest(BaseModel):
    action_id: str
    ticket_id: str
    window: str | None = None
    rollback_plan: str | None = None


@router.post("/runs/{run_id}/change-tickets")
def submit_ticket(run_id: str, req: ChangeTicketRequest) -> ApiResponse:
    return ApiResponse.success("", {
        "run_id": run_id,
        "action_id": req.action_id,
        "ticket_id": req.ticket_id,
        "status": "submitted",
    })
