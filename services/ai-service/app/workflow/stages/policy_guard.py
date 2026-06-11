"""Policy Guard — Policy Matrix 기반 4판정 결정론적 단계."""
from __future__ import annotations

from datetime import datetime, timezone
from uuid import uuid4

from app.catalogs import policy_matrix
from app.persistence.event_repository import AnyEventRepo, append_event
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.outputs import ActionCandidateOutput, PolicyDecisionOutput, PolicyGuardOutput
from app.schemas.state import ActionStatus, PolicyDecisionType
from app.streaming.event_bus import EventBus

_DECISION_TO_STATUS = {
    PolicyDecisionType.ALLOW: ActionStatus.READY,
    PolicyDecisionType.REQUIRE_APPROVAL: ActionStatus.PENDING_APPROVAL,
    PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT: ActionStatus.PENDING_APPROVAL,
    PolicyDecisionType.DENY: ActionStatus.BLOCKED,
}


async def _pub(bus: EventBus, repo: AnyEventRepo, run_id: str, event: StreamingEvent) -> None:
    await append_event(repo, run_id, event)
    await bus.publish(run_id, event)


async def run_policy_guard(
    action_candidates: list[ActionCandidateOutput],
    *,
    bus: EventBus,
    event_repo: AnyEventRepo,
    run_id: str,
) -> PolicyGuardOutput:
    decisions = []
    for c in action_candidates:
        rule = policy_matrix.lookup(c.action_type, c.risk)
        status = _DECISION_TO_STATUS[rule.decision]

        decision = PolicyDecisionOutput(
            action_id=c.action_id,
            action_type=c.action_type,
            risk=c.risk,
            decision=rule.decision,
            status=status,
            reason=rule.reason,
            tool_name=c.tool_name,
            tool_params=c.tool_params,
        )
        decisions.append(decision)

        if decision.decision == PolicyDecisionType.REQUIRE_APPROVAL:
            await _pub(bus, event_repo, run_id, StreamingEvent(
                event_id=str(uuid4()),
                run_id=run_id,
                timestamp=datetime.now(timezone.utc),
                type=StreamingEventType.APPROVAL_REQUIRED,
                agent="policy_guard",
                message="고위험 조치 — 승인 필요",
                payload={"action_id": decision.action_id, "reason": decision.reason},
            ))
        elif decision.decision == PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT:
            await _pub(bus, event_repo, run_id, StreamingEvent(
                event_id=str(uuid4()),
                run_id=run_id,
                timestamp=datetime.now(timezone.utc),
                type=StreamingEventType.CHANGE_MANAGEMENT_REQUIRED,
                agent="policy_guard",
                message="변경관리 티켓 필요",
                payload={
                    "action_id": decision.action_id,
                    "reason": decision.reason,
                    "required_fields": ["ticket", "window", "rollback_plan"],
                },
            ))

    return PolicyGuardOutput(policy_decisions=decisions)
