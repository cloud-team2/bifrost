"""Approval Gate — 사람 승인 기록 결정론적 단계."""
from __future__ import annotations

from app.persistence.approval_link_repository import ApprovalLink, get_approval_repo, spring_params_hash
from app.schemas.outputs import ApprovedActionOutput, ApprovalGateOutput
from app.schemas.state import ActionStatus, PolicyDecision, PolicyDecisionType, RunStatus


def _approval_params(decision: PolicyDecision) -> dict:
    return {
        "tool_name": decision.tool_name,
        "tool_params": decision.tool_params or {},
    }


async def run_approval_gate(
    policy_decisions: list[PolicyDecision],
    run_id: str,
    project_id: str | None = None,
) -> ApprovalGateOutput:
    """pending_approval action에 approval link를 생성하고 run을 WAITING_FOR_APPROVAL로 전환.

    이미 승인된 action은 approved_actions로 반환하고 실행 경로를 계속 진행한다.
    spring_approval_id가 설정되어 있으면 Spring MutationGate용 UUID를 approval_id로 사용한다.
    """
    repo = get_approval_repo()
    approved_actions: list[ApprovedActionOutput] = []
    has_pending = False

    for decision in policy_decisions:
        if (
            decision.status == ActionStatus.PENDING_APPROVAL
            and decision.decision == PolicyDecisionType.REQUIRE_APPROVAL
        ):
            existing: ApprovalLink | None = repo.get_by_action(run_id, decision.action_id)

            if existing and existing.status == "approved":
                # spring_approval_id가 있으면 Spring MutationGate에서 검증 가능한 UUID 사용
                approval_id = existing.spring_approval_id or existing.approval_id
                approved_actions.append(ApprovedActionOutput(
                    approval_id=approval_id,
                    action_id=existing.action_id,
                    params_hash=existing.spring_params_hash or existing.params_hash,
                    approved_by=existing.approved_by,
                ))
            elif existing and existing.status == "rejected":
                pass  # blocked — Executor가 READY 아닌 action 건너뜀
            else:
                link = repo.create(run_id=run_id, action_id=decision.action_id, params=_approval_params(decision))
                link.tool_name = decision.tool_name
                # Spring params_hash 사전 계산 (사용자 승인 시 Spring 레코드 생성에 사용)
                if project_id and decision.tool_name:
                    s_hash = spring_params_hash(
                        tool_name=decision.tool_name,
                        project_id=project_id,
                        tool_params=decision.tool_params,
                    )
                    link.spring_params_hash = s_hash
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
