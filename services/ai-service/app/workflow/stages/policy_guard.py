"""Policy Guard skeleton — Policy Matrix 기반 결정론적 단계."""
from __future__ import annotations

from app.schemas.outputs import ActionCandidateOutput, PolicyDecisionOutput, PolicyGuardOutput
from app.schemas.state import ActionStatus, PolicyDecisionType


async def run_policy_guard(action_candidates: list[ActionCandidateOutput]) -> PolicyGuardOutput:
    decisions = [
        PolicyDecisionOutput(
            action_id=c.action_id,
            action_type=c.action_type,
            risk=c.risk,
            decision=PolicyDecisionType.REQUIRE_APPROVAL,
            status=ActionStatus.PENDING_APPROVAL,
            reason="skeleton: Policy Matrix 미구현 — require_approval",
            tool_name=c.tool_name,
        )
        for c in action_candidates
    ]
    return PolicyGuardOutput(policy_decisions=decisions)
