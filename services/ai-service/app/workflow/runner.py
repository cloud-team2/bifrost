"""Workflow runner — 5 agent 순서 실행 + SSE 이벤트 발행.

simple_query 경로: router → planner → retrieval → verifier → report
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from uuid import uuid4

from app.agents import planner as planner_agent
from app.agents import report as report_agent
from app.agents import retrieval as retrieval_agent
from app.agents import router as router_agent
from app.agents import verifier as verifier_agent
from app.llm.provider import get_llm_provider
from app.persistence.event_repository import AnyEventRepo, InMemoryEventRepository, get_event_repo
from app.persistence.run_repository import InMemoryRunRepository
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.tools import ToolContext
from app.streaming.event_bus import EventBus
from app.tools.registry import ToolClientRegistry

logger = logging.getLogger(__name__)


def _evt(run_id: str, type_: StreamingEventType, agent: str | None, message: str, payload: dict | None = None) -> StreamingEvent:
    return StreamingEvent(
        event_id=str(uuid4()),
        run_id=run_id,
        timestamp=datetime.now(timezone.utc),
        type=type_,
        agent=agent,
        message=message,
        payload=payload or {},
    )


async def _publish(bus: EventBus, event_repo: AnyEventRepo, run_id: str, event: StreamingEvent) -> None:
    if isinstance(event_repo, InMemoryEventRepository):
        event_repo.append(run_id, event)
    else:
        await event_repo.append(run_id, event)
    await bus.publish(run_id, event)


async def run_workflow(
    *,
    run_id: str,
    user_message: str,
    project_id: str,
    bus: EventBus,
    run_repo: InMemoryRunRepository,
    registry: ToolClientRegistry,
) -> None:
    event_repo = get_event_repo()
    try:
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.RUN_STARTED, None, "분석을 시작합니다"))

        # ── Router ────────────────────────────────────────────────────────────
        run_repo.update_status(run_id, "running", "router")
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.AGENT_STARTED, "router", "질문 유형을 파악합니다"))
        router_out = await router_agent.run_router(user_message)
        mode = router_out.route_decision.mode
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.AGENT_COMPLETED, "router", f"mode: {mode.value}"))

        # ── Planner ───────────────────────────────────────────────────────────
        run_repo.update_status(run_id, "running", "planner")
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.AGENT_STARTED, "planner", "데이터 조회 계획을 수립합니다"))
        planner_out = await planner_agent.run_planner(user_message, project_id)
        tool_names = ", ".join(s.tool_name for s in planner_out.retrieval_plan)
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.AGENT_COMPLETED, "planner", f"조회 도구: {tool_names}"))

        # ── Retrieval ─────────────────────────────────────────────────────────
        run_repo.update_status(run_id, "running", "retrieval")
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.AGENT_STARTED, "retrieval", "운영 데이터를 조회합니다"))
        context = ToolContext(
            run_id=run_id,
            step_id=str(uuid4()),
            agent_name="retrieval",
            project_id=project_id,
            request_id=str(uuid4()),
        )
        retrieval_out = await retrieval_agent.run_retrieval(run_id, planner_out, context, registry, bus, event_repo)
        await _publish(bus, event_repo, run_id, _evt(
            run_id, StreamingEventType.AGENT_COMPLETED, "retrieval",
            f"근거 {len(retrieval_out.evidence_items)}건 수집",
        ))

        # ── Verifier ──────────────────────────────────────────────────────────
        run_repo.update_status(run_id, "running", "verifier")
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.AGENT_STARTED, "verifier", "결과를 검증합니다"))
        verifier_out = await verifier_agent.run_verifier(mode)
        v_status = verifier_out.verification_results[0].status.value if verifier_out.verification_results else "pass"
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.VERIFICATION_COMPLETED, "verifier", f"검증: {v_status}"))

        # ── Report ────────────────────────────────────────────────────────────
        run_repo.update_status(run_id, "running", "report")
        await _publish(bus, event_repo, run_id, _evt(run_id, StreamingEventType.AGENT_STARTED, "report", "답변을 생성합니다"))
        llm = get_llm_provider()
        answer = await report_agent.run_report(user_message, retrieval_out, mode, llm)
        await _publish(bus, event_repo, run_id, _evt(
            run_id, StreamingEventType.PARTIAL_RESULT, "report",
            answer[:300],
            {"answer": answer},
        ))

        run_repo.update_status(run_id, "completed", None)
        await _publish(bus, event_repo, run_id, _evt(
            run_id, StreamingEventType.RUN_COMPLETED, "report",
            "분석이 완료되었습니다",
            {"answer": answer},
        ))

    except Exception as exc:
        logger.exception("run_workflow failed: run_id=%s", run_id)
        run_repo.update_status(run_id, "failed", None)
        await _publish(bus, event_repo, run_id, _evt(
            run_id, StreamingEventType.RUN_COMPLETED, None,
            f"오류: {exc}",
            {"error": str(exc)},
        ))

    finally:
        await bus.close_run(run_id)
