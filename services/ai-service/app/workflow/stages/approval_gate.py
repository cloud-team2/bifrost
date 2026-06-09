"""Approval Gate — 사람 승인 기록 결정론적 단계."""
from __future__ import annotations

from app.persistence.approval_link_repository import ApprovalLink, get_approval_repo
from app.schemas.outputs import ApprovedActionOutput, ApprovalGateOutput
from app.schemas.state import ActionStatus, PolicyDecision, RunStatus


async def run_approval_gate(
    policy_decisions: list[PolicyDecision],
    run_id: str,
) -> ApprovalGateOutput:
    """pending_approval action에 approval link를 생성하고 run을 WAITING_FOR_APPROVAL로 전환.

    이미 승인된 action은 approved_actions로 반환하고 실행 경로를 계속 진행한다.
    """
    repo = get_approval_repo()
    approved_actions: list[ApprovedActionOutput] = []
    has_pending = False

    for decision in policy_decisions:
        if decision.status == ActionStatus.PENDING_APPROVAL:
            existing: ApprovalLink | None = repo.get_by_action(run_id, decision.action_id)

            if existing and existing.status == "approved":
                approved_actions.append(ApprovedActionOutput(
                    approval_id=existing.approval_id,
                    action_id=existing.action_id,
                    params_hash=existing.params_hash,
                    approved_by=existing.approved_by,
                ))
            elif existing and existing.status == "rejected":
                pass  # blocked — Executor가 READY 아닌 action 건너뜀
            else:
                repo.create(run_id=run_id, action_id=decision.action_id, params={})
                has_pending = True

        elif decision.status == ActionStatus.READY:
            approved_actions.append(ApprovedActionOutput(
                approval_id=f"auto_{decision.action_id}",
                action_id=decision.action_id,
                params_hash="",
            ))

    run_status = (
        RunStatus.WAITING_FOR_APPROVAL.value if has_pending
        else RunStatus.RUNNING.value
    )
    return ApprovalGateOutput(approved_actions=approved_actions, run_status=run_status)
