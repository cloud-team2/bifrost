"""Policy Guard skeleton — Policy Matrix 기반 결정론적 단계."""
from __future__ import annotations

from datetime import datetime, timezone
from uuid import uuid4

from app.persistence.event_repository import AnyEventRepo, InMemoryEventRepository
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.outputs import ActionCandidateOutput, PolicyDecisionOutput, PolicyGuardOutput
from app.schemas.state import ActionStatus, PolicyDecisionType
from app.streaming.event_bus import EventBus


async def _pub(bus: EventBus, repo: AnyEventRepo, run_id: str, event: StreamingEvent) -> None:
    if isinstance(repo, InMemoryEventRepository):
        repo.append(run_id, event)
    else:
        await repo.append(run_id, event)
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
        decision = PolicyDecisionOutput(
            action_id=c.action_id,
            action_type=c.action_type,
            risk=c.risk,
            decision=PolicyDecisionType.REQUIRE_APPROVAL,
            status=ActionStatus.PENDING_APPROVAL,
            reason="skeleton: Policy Matrix 미구현 — require_approval",
            tool_name=c.tool_name,
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
