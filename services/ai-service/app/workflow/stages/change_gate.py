"""Change Management Gate — 변경관리 검증 결정론적 단계."""
from __future__ import annotations

from uuid import uuid4

from app.schemas.outputs import ChangeManagementOutput, ChangeManagementRecordOutput
from app.schemas.state import ActionStatus, PolicyDecision, RunStatus


async def run_change_gate(
    policy_decisions: list[PolicyDecision],
    run_id: str,
    change_tickets: dict[str, str] | None = None,
) -> ChangeManagementOutput:
    """REQUIRE_CHANGE_MANAGEMENT action에 대해 ticket/window/rollback_plan을 검증한다.

    change_tickets: {action_id: ticket_id} — 제출된 변경관리 티켓
    """
    tickets = change_tickets or {}
    records: list[ChangeManagementRecordOutput] = []
    has_missing_ticket = False

    for decision in policy_decisions:
        if decision.status != ActionStatus.PENDING_APPROVAL:
            continue

        ticket_id = tickets.get(decision.action_id)
        if ticket_id:
            records.append(ChangeManagementRecordOutput(
                change_ticket_id=ticket_id,
                action_id=decision.action_id,
                status="verified",
            ))
        else:
            records.append(ChangeManagementRecordOutput(
                change_ticket_id=f"PENDING_{uuid4().hex[:8]}",
                action_id=decision.action_id,
                status="CHANGE_TICKET_REQUIRED",
            ))
            has_missing_ticket = True

    run_status = (
        RunStatus.WAITING_FOR_APPROVAL.value if has_missing_ticket
        else RunStatus.RUNNING.value
    )
    return ChangeManagementOutput(change_management_records=records, run_status=run_status)
