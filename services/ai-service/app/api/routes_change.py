"""Change Management Gate API — 변경관리 티켓 제출 엔드포인트."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel

from app.persistence.change_ticket_repository import ChangeTicket, get_change_ticket_repo
from app.schemas import ApiResponse
from app.workflow.stages.change_gate import verify_change_ticket

router = APIRouter()


class ChangeTicketRequest(BaseModel):
    action_id: str
    ticket_id: str
    window: str | None = None
    rollback_plan: str | None = None


@router.post("/runs/{run_id}/change-tickets")
async def submit_ticket(run_id: str, req: ChangeTicketRequest) -> ApiResponse:
    repo = get_change_ticket_repo()
    await repo.upsert(
        run_id=run_id,
        action_id=req.action_id,
        ticket_id=req.ticket_id,
        window=req.window,
        rollback_plan=req.rollback_plan,
    )

    gate_output = await verify_change_ticket(run_id, req.action_id)
    ticket = await repo.get_by_action(run_id, req.action_id)
    record = next(
        (
            record for record in gate_output.change_management_records
            if record.action_id == req.action_id
        ),
        None,
    )

    return ApiResponse.success("", {
        "run_id": run_id,
        "action_id": req.action_id,
        "ticket_id": ticket.ticket_id if ticket else req.ticket_id,
        "window": ticket.window if ticket else req.window,
        "rollback_plan": ticket.rollback_plan if ticket else req.rollback_plan,
        "status": record.status if record else (ticket.status if ticket else None),
        "run_status": gate_output.run_status,
        "change_management_records": [
            record.model_dump(mode="json")
            for record in gate_output.change_management_records
        ],
    })


@router.get("/runs/{run_id}/change-tickets")
async def list_tickets(run_id: str) -> ApiResponse:
    tickets = await get_change_ticket_repo().list_by_run(run_id)
    return ApiResponse.success("", {
        "run_id": run_id,
        "change_tickets": [_ticket_response(ticket) for ticket in tickets],
    })


def _ticket_response(ticket: ChangeTicket) -> dict:
    return {
        "run_id": ticket.run_id,
        "action_id": ticket.action_id,
        "ticket_id": ticket.ticket_id,
        "window": ticket.window,
        "rollback_plan": ticket.rollback_plan,
        "status": ticket.status,
        "created_at": ticket.created_at.isoformat() if ticket.created_at else None,
        "updated_at": ticket.updated_at.isoformat() if ticket.updated_at else None,
    }
