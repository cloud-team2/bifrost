"""Change Management Gate — 변경관리 검증 결정론적 단계."""
from __future__ import annotations

from typing import Protocol
from uuid import uuid4

from app.persistence.change_ticket_repository import (
    ChangeTicket,
    STATUS_CHANGE_TICKET_REQUIRED,
    STATUS_CHANGE_WINDOW_REQUIRED,
    STATUS_ROLLBACK_PLAN_REQUIRED,
    STATUS_VERIFIED,
    get_change_ticket_repo,
)
from app.schemas.outputs import ChangeManagementOutput, ChangeManagementRecordOutput
from app.schemas.state import ActionStatus, PolicyDecisionType, RunStatus


class ChangeGateDecision(Protocol):
    action_id: str
    decision: PolicyDecisionType
    status: ActionStatus


async def verify_change_ticket(run_id: str, action_id: str) -> ChangeManagementOutput:
    """Persisted ticket 하나를 change gate 규칙으로 검증하고 저장 상태를 갱신한다.

    routes_change는 실제 policy decision을 만들 수 없으므로 run/action의 티켓 자체만
    검증한다. policy decision 필터링은 workflow의 run_change_gate가 담당한다.
    """
    repo = get_change_ticket_repo()
    ticket = await repo.get_by_action(run_id, action_id)
    record = _record_for_ticket(action_id, ticket, require_metadata=True)
    if ticket:
        await repo.update_status(run_id, action_id, record.status)
    return _output_from_records([record])


async def run_change_gate(
    policy_decisions: list[ChangeGateDecision],
    run_id: str,
    change_tickets: dict[str, str] | None = None,
) -> ChangeManagementOutput:
    """REQUIRE_CHANGE_MANAGEMENT action에 대해 ticket/window/rollback_plan을 검증한다.

    change_tickets는 과거 테스트/호환용 명시 입력이다. 이 dict shape에는 window와
    rollback_plan이 없으므로 production 경로(route/runner)는 항상 repository에
    영속된 ChangeTicket을 사용한다.
    """
    require_metadata = change_tickets is None
    tickets = await _load_tickets(run_id, change_tickets)
    repo = get_change_ticket_repo()

    records: list[ChangeManagementRecordOutput] = []
    for decision in policy_decisions:
        if decision.status != ActionStatus.PENDING_APPROVAL:
            continue
        if decision.decision != PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT:
            continue

        ticket = tickets.get(decision.action_id)
        record = _record_for_ticket(
            decision.action_id,
            ticket,
            require_metadata=require_metadata,
        )
        records.append(record)

        if ticket and require_metadata:
            await repo.update_status(run_id, decision.action_id, record.status)

    return _output_from_records(records)


async def _load_tickets(
    run_id: str,
    change_tickets: dict[str, str] | None,
) -> dict[str, ChangeTicket]:
    if change_tickets is not None:
        return {
            action_id: ChangeTicket(
                run_id=run_id,
                action_id=action_id,
                ticket_id=ticket_id,
            )
            for action_id, ticket_id in change_tickets.items()
        }

    persisted = await get_change_ticket_repo().list_by_run(run_id)
    return {ticket.action_id: ticket for ticket in persisted}


def _record_for_ticket(
    action_id: str,
    ticket: ChangeTicket | None,
    *,
    require_metadata: bool,
) -> ChangeManagementRecordOutput:
    status = _validation_status(ticket, require_metadata=require_metadata)
    change_ticket_id = ticket.ticket_id if ticket else f"PENDING_{uuid4().hex[:8]}"
    return ChangeManagementRecordOutput(
        change_ticket_id=change_ticket_id,
        action_id=action_id,
        status=status,
    )


def _validation_status(ticket: ChangeTicket | None, *, require_metadata: bool) -> str:
    if ticket is None or not ticket.ticket_id.strip():
        return STATUS_CHANGE_TICKET_REQUIRED
    if require_metadata and not (ticket.window or "").strip():
        return STATUS_CHANGE_WINDOW_REQUIRED
    if require_metadata and not (ticket.rollback_plan or "").strip():
        return STATUS_ROLLBACK_PLAN_REQUIRED
    return STATUS_VERIFIED


def _output_from_records(records: list[ChangeManagementRecordOutput]) -> ChangeManagementOutput:
    has_unverified_ticket = any(record.status != STATUS_VERIFIED for record in records)
    run_status = (
        RunStatus.WAITING_FOR_APPROVAL.value if has_unverified_ticket
        else RunStatus.RUNNING.value
    )
    return ChangeManagementOutput(change_management_records=records, run_status=run_status)
