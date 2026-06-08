"""Workflow runner — Supervisor 상태 전이 기반 실행 + SSE 이벤트 발행.

simple_query 경로: router(pre-supervisor) → planner → retrieval → verifier → report
incident_analysis 경로: router → correlation → planner → retrieval → classifier → rca → verifier → report
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from uuid import uuid4

from app.agents import classifier as classifier_agent
from app.agents import planner as planner_agent
from app.agents import rca as rca_agent
from app.agents import remediation as remediation_agent
from app.agents import report as report_agent
from app.agents import retrieval as retrieval_agent
from app.agents import router as router_agent
from app.agents import verifier as verifier_agent
from app.llm.provider import get_llm_provider
from app.persistence.event_repository import AnyEventRepo, InMemoryEventRepository, get_event_repo
from app.persistence.run_repository import AnyRunRepo
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.tools import ToolContext
from app.streaming.event_bus import EventBus
from app.supervisor.graph import get_supervisor
from app.tools.registry import ToolClientRegistry
from app.workflow.guards import RunBudgetExceeded
from app.workflow.stages.correlation import run_correlation
from app.workflow.stages.policy_guard import run_policy_guard

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
    run_repo: AnyRunRepo,
    registry: ToolClientRegistry,
) -> None:
    event_repo = get_event_repo()
    supervisor = get_supervisor()
    answer: str | None = None  # report 단계 전 budget 초과 대비

    try:
        await _publish(bus, event_repo, run_id,
                       _evt(run_id, StreamingEventType.RUN_STARTED, None, "분석을 시작합니다"))

        # ── Router: Supervisor 외부에서 mode 결정 ─────────────────────────────
        await run_repo.update_status(run_id, "running", "router")
        await _publish(bus, event_repo, run_id,
                       _evt(run_id, StreamingEventType.AGENT_STARTED, "router", "질문 유형을 파악합니다"))
        router_out = await router_agent.run_router(user_message)
        mode = router_out.route_decision.mode
        await _publish(bus, event_repo, run_id,
                       _evt(run_id, StreamingEventType.AGENT_COMPLETED, "router", f"mode: {mode.value}"))

        # ── Supervisor 초기화 ──────────────────────────────────────────────────
        supervisor.start_run(
            run_id, mode,
            remediation_requested=router_out.route_decision.remediation_requested,
        )

        # ── Stage 루프 ─────────────────────────────────────────────────────────
        planner_out = None
        retrieval_out = None
        classifier_out = None
        rca_out = None
        remediation_out = None

        while True:
            try:
                stage = supervisor.advance(run_id)
            except RunBudgetExceeded as exc:
                await run_repo.update_status(run_id, "failed", None)
                await _publish(bus, event_repo, run_id, _evt(
                    run_id, StreamingEventType.RUN_COMPLETED, None,
                    f"실행 예산 초과: {exc.reason}",
                    {"error": str(exc)},
                ))
                return

            if stage is None:
                break

            await run_repo.update_status(run_id, "running", stage)

            match stage:
                case "correlation":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "correlation", "알림 이벤트를 분석합니다"))
                    await run_correlation()
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_COMPLETED, "correlation", "알림 그룹 분석 완료"))

                case "planner":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "planner", "데이터 조회 계획을 수립합니다"))
                    planner_out = await planner_agent.run_planner(user_message, project_id)
                    tool_names = ", ".join(s.tool_name for s in planner_out.retrieval_plan)
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_COMPLETED, "planner", f"조회 도구: {tool_names}"))
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.PARTIAL_RESULT, "planner",
                        f"조회 계획 확정: {tool_names}",
                        {"stage": "planner", "plan": [s.tool_name for s in planner_out.retrieval_plan]},
                    ))

                case "retrieval":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "retrieval", "운영 데이터를 조회합니다"))
                    context = ToolContext(
                        run_id=run_id,
                        step_id=str(uuid4()),
                        agent_name="retrieval",
                        project_id=project_id,
                        request_id=str(uuid4()),
                    )
                    retrieval_out = await retrieval_agent.run_retrieval(
                        run_id, planner_out, context, registry, bus, event_repo
                    )
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "retrieval",
                        f"근거 {len(retrieval_out.evidence_items)}건 수집",
                    ))

                case "classifier":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "classifier", "장애 유형을 분류합니다"))
                    classifier_out = await classifier_agent.run_classifier(user_message, retrieval_out)
                    scope = classifier_out.classification.incident_scope.value
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_COMPLETED, "classifier", f"scope: {scope}"))
                    clf = classifier_out.classification
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.PARTIAL_RESULT, "classifier",
                        f"incident 유형 분류: scope={clf.incident_scope.value}",
                        {
                            "stage": "classifier",
                            "scope": clf.incident_scope.value,
                            "types": [t.type for t in clf.incident_types],
                        },
                    ))

                case "rca":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "rca", "근본 원인을 분석합니다"))
                    rca_out = await rca_agent.run_rca(classifier_out, retrieval_out)
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "rca",
                        f"후보 {len(rca_out.root_cause_candidates)}건",
                    ))
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.PARTIAL_RESULT, "rca",
                        f"근본 원인 후보 {len(rca_out.root_cause_candidates)}건",
                        {"stage": "rca", "candidates": len(rca_out.root_cause_candidates)},
                    ))
                    if rca_out.root_cause_candidates:
                        top = rca_out.root_cause_candidates[0]
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id, StreamingEventType.REPORT_PREVIEW_AVAILABLE, "rca",
                            f"[검증 전 preview] 원인 후보: {top.root_cause_id} (confidence: {top.confidence:.0%})",
                            {"root_cause_id": top.root_cause_id, "confidence": top.confidence, "verified": False},
                        ))

                case "remediation":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "remediation", "조치 후보를 생성합니다"))
                    remediation_out = await remediation_agent.run_remediation(rca_out)
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "remediation",
                        f"후보 {len(remediation_out.action_candidates)}건",
                    ))

                case "policy_guard":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "policy_guard", "정책을 확인합니다"))
                    candidates = remediation_out.action_candidates if remediation_out else []
                    policy_out = await run_policy_guard(candidates, bus=bus, event_repo=event_repo, run_id=run_id)
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "policy_guard",
                        f"결정 {len(policy_out.policy_decisions)}건",
                    ))

                case "verifier":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "verifier", "결과를 검증합니다"))
                    verifier_out = await verifier_agent.run_verifier(mode)
                    v_status = verifier_out.verification_results[0].status.value if verifier_out.verification_results else "pass"
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.VERIFICATION_COMPLETED, "verifier", f"검증: {v_status}"))

                case "report":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "report", "답변을 생성합니다"))
                    llm = get_llm_provider()
                    answer = await report_agent.run_report(user_message, retrieval_out, mode, llm)
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.PARTIAL_RESULT, "report",
                        answer[:300],
                        {"answer": answer},
                    ))

                case _:
                    logger.warning("unknown stage skipped: %s", stage)

        await run_repo.update_status(run_id, "completed", None)
        await _publish(bus, event_repo, run_id, _evt(
            run_id, StreamingEventType.RUN_COMPLETED, "report",
            "분석이 완료되었습니다",
            {"answer": answer or ""},
        ))

    except Exception as exc:
        logger.exception("run_workflow failed: run_id=%s", run_id)
        await run_repo.update_status(run_id, "failed", None)
        await _publish(bus, event_repo, run_id, _evt(
            run_id, StreamingEventType.RUN_COMPLETED, None,
            f"오류: {exc}",
            {"error": str(exc)},
        ))

    finally:
        await bus.close_run(run_id)
