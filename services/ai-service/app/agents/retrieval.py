"""Retrieval agent — fan-out tool calls, mock fallback when Spring unavailable."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from uuid import uuid4

from app.persistence.event_repository import AnyEventRepo, InMemoryEventRepository
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.outputs import PlannerOutput, RetrievalOutput
from app.schemas.state import EvidenceItem, EvidenceType, RedactionStatus
from app.schemas.tools import ToolContext
from app.streaming.event_bus import EventBus
from app.tools.registry import ToolClientRegistry


async def _pub(bus: EventBus, repo: AnyEventRepo, run_id: str, event: StreamingEvent) -> None:
    if isinstance(repo, InMemoryEventRepository):
        repo.append(run_id, event)
    else:
        await repo.append(run_id, event)
    await bus.publish(run_id, event)


async def run_retrieval(
    run_id: str,
    plan: PlannerOutput,
    context: ToolContext,
    registry: ToolClientRegistry,
    bus: EventBus,
    event_repo: AnyEventRepo | None = None,
) -> RetrievalOutput:
    async def call_step(step: "RetrievalPlanStep") -> EvidenceItem:  # type: ignore[name-defined]
        now = datetime.now(timezone.utc)
        await _pub(bus, event_repo, run_id, StreamingEvent(
            event_id=str(uuid4()),
            run_id=run_id,
            timestamp=now,
            type=StreamingEventType.TOOL_CALL_STARTED,
            agent="retrieval",
            message=f"{step.tool_name} 조회 중...",
            payload={"tool": step.tool_name, "step_id": step.step_id},
        ))

        result = await registry.call_tool(step.tool_name, step.params, context)

        if result.status.value == "success":
            summary = result.summary
            store_ref = f"evidence://{run_id}/{step.step_id}"
        else:
            summary = f"[mock] {step.tool_name}: Spring 미연결, 시뮬레이션 결과 반환"
            store_ref = f"evidence://{run_id}/{step.step_id}_mock"

        evidence = EvidenceItem(
            evidence_id=str(uuid4()),
            type=EvidenceType.TOOL_RESULT,
            store_ref=store_ref,
            summary=summary,
            redaction_status=RedactionStatus.REDACTED,
            collected_by="retrieval",
            collected_at=datetime.now(timezone.utc),
        )

        await _pub(bus, event_repo, run_id, StreamingEvent(
            event_id=str(uuid4()),
            run_id=run_id,
            timestamp=datetime.now(timezone.utc),
            type=StreamingEventType.TOOL_CALL_COMPLETED,
            agent="retrieval",
            message=summary,
            payload={"tool": step.tool_name, "step_id": step.step_id, "summary": summary},
        ))

        return evidence

    # 독립 read-only tool은 병렬(fan-out) 실행
    evidence_items = list(await asyncio.gather(*[call_step(s) for s in plan.retrieval_plan]))
    return RetrievalOutput(evidence_items=evidence_items)
