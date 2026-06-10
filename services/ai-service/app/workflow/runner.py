"""Workflow runner — Supervisor 상태 전이 기반 실행 + SSE 이벤트 발행.

simple_query 경로: planner → retrieval → verifier → report
incident_analysis 경로: correlation → planner → retrieval → classifier → rca → verifier → report
action_execution 경로: policy_guard → approval_gate → change_gate → executor → verifier → report
approval_decision 경로: approval_gate → executor → verifier → report
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any
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
from app.persistence.change_ticket_repository import STATUS_VERIFIED
from app.persistence.event_repository import AnyEventRepo, append_event, get_event_repo
from app.persistence.report_repository import get_report_repo
from app.persistence.run_repository import AnyRunRepo
from app.persistence.state_repository import get_state_repo
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.state import ActionCandidate, ActionStatus
from app.schemas.tools import ToolContext
from app.streaming.event_bus import EventBus
from app.supervisor.graph import get_supervisor
from app.tools.registry import ToolClientRegistry
from app.workflow.guards import RunBudgetExceeded
from app.workflow.stages.approval_gate import run_approval_gate
from app.workflow.stages.change_gate import run_change_gate
from app.workflow.stages.correlation import run_correlation
from app.workflow.stages.executor import run_executor
from app.workflow.stages.policy_guard import run_policy_guard

logger = logging.getLogger(__name__)
_PUBLIC_FAILURE_MESSAGE = "요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."


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
    await append_event(event_repo, run_id, event)
    await bus.publish(run_id, event)


async def _publish_failure(bus: EventBus, event_repo: AnyEventRepo, run_id: str) -> None:
    event = _evt(
        run_id,
        StreamingEventType.RUN_COMPLETED,
        None,
        _PUBLIC_FAILURE_MESSAGE,
        {"error": "workflow_failed"},
    )
    try:
        await append_event(event_repo, run_id, event)
    except Exception:
        logger.warning("final failure event persist failed: run_id=%s", run_id, exc_info=True)
    await bus.publish(run_id, event)


async def _append_state_patch(
    state_repo: Any,
    run_id: str,
    namespace: str,
    author: str,
    op: str,
    path: str,
    patch: dict[str, Any],
) -> None:
    try:
        await state_repo.append(run_id, namespace, author, op, path, patch)
    except Exception as exc:
        logger.warning(
            "state_patch persist failed: run=%s namespace=%s error=%s",
            run_id,
            namespace,
            exc,
        )


def _jsonable(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump(mode="json")
    if isinstance(value, list):
        return [_jsonable(item) for item in value]
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    if hasattr(value, "value"):
        return value.value
    return value


def _evidence_patch(evidence) -> dict[str, Any]:
    return {
        "evidence_id": evidence.evidence_id,
        "type": _jsonable(evidence.type),
        "store_ref": evidence.store_ref,
        "summary": evidence.summary,
        "redaction_status": _jsonable(evidence.redaction_status),
    }


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
    state_repo = get_state_repo()
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
        incident_id = getattr(router_out.route_decision, "incident_id", None)
        if incident_id:
            await _append_state_patch(
                state_repo,
                run_id,
                namespace="incident",
                author="Router",
                op="append",
                path="/incident/incident_id",
                patch={"incident_id": incident_id, "mode": mode.value},
            )

        # ── Supervisor 초기화 ──────────────────────────────────────────────────
        supervisor.start_run(
            run_id, mode,
            remediation_requested=router_out.route_decision.remediation_requested,
        )

        # ── Stage 루프 ─────────────────────────────────────────────────────────
        correlation_out = None
        planner_out = None
        retrieval_out = None
        classifier_out = None
        rca_out = None
        remediation_out = None
        policy_out = None
        approval_out = None
        change_out = None
        executor_out = None
        verifier_out = None

        while True:
            try:
                stage = supervisor.advance(run_id)
            except RunBudgetExceeded as exc:
                await _append_state_patch(
                    state_repo,
                    run_id,
                    namespace="run",
                    author="Supervisor",
                    op="version",
                    path="/run/guards",
                    patch={"reason": exc.reason},
                )
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
                    correlation_out = await run_correlation(user_message=user_message)
                    scope_label = correlation_out.scope.value
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "correlation",
                        f"scope={scope_label}, groups={len(correlation_out.groups)}",
                    ))
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="correlation",
                        author="CorrelationEngine",
                        op="append",
                        path="/correlation",
                        patch={
                            "correlation_id": correlation_out.correlation_id,
                            "scope": scope_label,
                            "groups": _jsonable(correlation_out.groups),
                            "related_alert_ids": correlation_out.related_alert_ids,
                        },
                    )

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
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="run.plan",
                        author="Planner",
                        op="append",
                        path="/run/plan/executed_plan_hashes",
                        patch={
                            "executed_plan_hashes": [s.plan_hash for s in planner_out.retrieval_plan],
                            "steps": _jsonable(planner_out.retrieval_plan),
                        },
                    )

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
                        run_id,
                        planner_out,
                        context,
                        registry,
                        bus,
                        event_repo,
                        user_message=user_message,
                        mode=mode,
                    )
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "retrieval",
                        f"근거 {len(retrieval_out.evidence_items)}건 수집",
                    ))
                    for evidence in retrieval_out.evidence_items:
                        await _append_state_patch(
                            state_repo,
                            run_id,
                            namespace="evidence",
                            author="Retrieval",
                            op="append",
                            path="/evidence/items",
                            patch=_evidence_patch(evidence),
                        )

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
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="analysis",
                        author="Classifier",
                        op="append",
                        path="/analysis/incident_types",
                        patch={
                            "incident_types": _jsonable(clf.incident_types),
                            "scope": clf.incident_scope.value,
                        },
                    )
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="incident",
                        author="Classifier",
                        op="append",
                        path="/incident/scope",
                        patch={"scope": clf.incident_scope.value},
                    )

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
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="analysis",
                        author="RCA",
                        op="append",
                        path="/analysis/root_cause_candidates",
                        patch={"root_cause_candidates": _jsonable(rca_out.root_cause_candidates)},
                    )
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
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="actions",
                        author="Remediation",
                        op="append",
                        path="/actions/candidates",
                        patch={"candidates": _jsonable(remediation_out.action_candidates)},
                    )

                case "policy_guard":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "policy_guard", "정책을 확인합니다"))
                    candidates = remediation_out.action_candidates if remediation_out else []
                    policy_out = await run_policy_guard(candidates, bus=bus, event_repo=event_repo, run_id=run_id)
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "policy_guard",
                        f"결정 {len(policy_out.policy_decisions)}건",
                    ))
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="actions",
                        author="PolicyGuard",
                        op="append",
                        path="/actions/policy_decisions",
                        patch={"policy_decisions": _jsonable(policy_out.policy_decisions)},
                    )
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="actions",
                        author="PolicyGuard",
                        op="append",
                        path="/actions/approval_requests",
                        patch={
                            "approval_requests": [
                                {
                                    "action_id": decision.action_id,
                                    "decision": decision.decision.value,
                                    "status": decision.status.value,
                                    "required_approver": decision.required_approver,
                                }
                                for decision in policy_out.policy_decisions
                                if decision.status == ActionStatus.PENDING_APPROVAL
                            ]
                        },
                    )

                case "approval_gate":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "approval_gate", "승인 상태를 확인합니다"))
                    decisions = policy_out.policy_decisions if policy_out else []
                    approval_out = await run_approval_gate(decisions, run_id)
                    if approval_out.run_status == "waiting_for_approval":
                        await run_repo.update_status(run_id, "waiting_for_approval", "approval_gate")
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "approval_gate",
                        f"승인 {len(approval_out.approved_actions)}건, status={approval_out.run_status}",
                    ))
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="actions",
                        author="HumanApprovalGate",
                        op="append",
                        path="/actions/approved_actions",
                        patch={"approved_actions": _jsonable(approval_out.approved_actions)},
                    )

                case "change_gate":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "change_gate", "변경관리를 확인합니다"))
                    decisions = policy_out.policy_decisions if policy_out else []
                    change_out = await run_change_gate(decisions, run_id)
                    if change_out.run_status == "waiting_for_approval":
                        await run_repo.update_status(run_id, "waiting_for_approval", "change_gate")
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "change_gate",
                        f"변경관리 {len(change_out.change_management_records)}건, status={change_out.run_status}",
                    ))
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="actions",
                        author="ChangeManagementGate",
                        op="append",
                        path="/actions/change_management_records",
                        patch={"change_management_records": _jsonable(change_out.change_management_records)},
                    )
                    if change_out.run_status == "waiting_for_approval":
                        return

                case "executor":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "executor", "조치를 실행합니다"))
                    exec_context = ToolContext(
                        run_id=run_id,
                        step_id=str(uuid4()),
                        agent_name="executor",
                        project_id=project_id,
                        request_id=str(uuid4()),
                    )
                    approved = approval_out.approved_actions if approval_out else []
                    change_ready_ids = [
                        record.action_id
                        for record in (change_out.change_management_records if change_out else [])
                        if record.status == STATUS_VERIFIED
                    ]
                    ready_candidates = [
                        candidate
                        for action_id in _dedupe_action_ids(
                            [a.action_id for a in approved] + change_ready_ids
                        )
                        if (candidate := _ready_action_candidate(action_id, remediation_out, decisions)) is not None
                    ]
                    executor_out = await run_executor(
                        ready_candidates,
                        run_id=run_id,
                        context=exec_context,
                        registry=registry,
                    )
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "executor",
                        f"실행 {len(executor_out.execution_results)}건",
                    ))
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="actions",
                        author="Executor",
                        op="append",
                        path="/actions/execution_results",
                        patch={"execution_results": _jsonable(executor_out.execution_results)},
                    )

                case "verifier":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "verifier", "결과를 검증합니다"))
                    verifier_out = await verifier_agent.run_verifier(
                        mode,
                        rca_out=rca_out,
                        retrieval_out=retrieval_out,
                        classifier_out=classifier_out,
                    )
                    v_status = verifier_out.verification_results[0].status.value if verifier_out.verification_results else "pass"
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.VERIFICATION_COMPLETED, "verifier", f"검증: {v_status}"))
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="verification",
                        author="Verifier",
                        op="append",
                        path="/verification/verification_results",
                        patch={"verification_results": _jsonable(verifier_out.verification_results)},
                    )

                case "report":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "report", "답변을 생성합니다"))
                    llm = get_llm_provider()
                    answer = await report_agent.run_report(user_message, retrieval_out, mode, llm)
                    await _persist_report_snapshot(
                        run_id=run_id,
                        answer=answer,
                        mode=mode,
                        retrieval_out=retrieval_out,
                        rca_out=rca_out,
                        verifier_out=verifier_out,
                    )
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="report",
                        author="Report",
                        op="append",
                        path="/report/draft",
                        patch={"draft": {"answer": answer}},
                    )
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
        await _publish_failure(bus, event_repo, run_id)

    finally:
        await bus.close_run(run_id)


def _find_tool_name(action_id: str, remediation_out) -> str | None:
    """remediation 결과에서 action_id에 해당하는 tool_name을 찾는다."""
    if remediation_out is None:
        return None
    for c in remediation_out.action_candidates:
        if c.action_id == action_id:
            return c.tool_name
    return None


def _dedupe_action_ids(action_ids: list[str]) -> list[str]:
    seen: set[str] = set()
    deduped: list[str] = []
    for action_id in action_ids:
        if action_id in seen:
            continue
        seen.add(action_id)
        deduped.append(action_id)
    return deduped


def _ready_action_candidate(action_id: str, remediation_out, policy_decisions) -> ActionCandidate | None:
    """실제 remediation/policy 결과를 사용해 executor-ready ActionCandidate를 만든다."""
    if remediation_out is not None:
        for candidate in remediation_out.action_candidates:
            if candidate.action_id == action_id:
                return ActionCandidate(
                    action_id=candidate.action_id,
                    action_type=candidate.action_type,
                    action_name=candidate.action_name,
                    root_cause_id=candidate.root_cause_id,
                    risk=candidate.risk,
                    reason="gate verified",
                    expected_effect=candidate.expected_effect,
                    rollback_plan=candidate.rollback_plan,
                    estimated_duration=candidate.estimated_duration,
                    tool_name=candidate.tool_name,
                    status=ActionStatus.READY,
                )

    for decision in policy_decisions:
        if decision.action_id == action_id:
            return ActionCandidate(
                action_id=decision.action_id,
                action_type=decision.action_type,
                action_name=decision.action_id,
                risk=decision.risk,
                reason=decision.reason,
                tool_name=decision.tool_name,
                status=ActionStatus.READY,
            )

    return None


async def _persist_report_snapshot(
    *,
    run_id: str,
    answer: str,
    mode,
    retrieval_out,
    rca_out,
    verifier_out,
) -> None:
    root_cause_id = None
    confidence = None
    if rca_out and rca_out.root_cause_candidates:
        top = rca_out.root_cause_candidates[0]
        root_cause_id = top.root_cause_id
        confidence = top.confidence

    verified = bool(
        verifier_out
        and any(result.approved_for_final_response for result in verifier_out.verification_results)
    )
    body = {
        "answer": answer,
        "mode": mode.value if hasattr(mode, "value") else str(mode),
        "evidence": [
            item.model_dump(mode="json")
            for item in (retrieval_out.evidence_items if retrieval_out else [])
        ],
    }

    try:
        await get_report_repo().create(
            run_id,
            body,
            root_cause_id=root_cause_id,
            confidence=confidence,
            verified=verified,
        )
    except Exception as exc:  # report cache failure must not hide the final answer
        logger.warning("report snapshot persistence failed: run_id=%s error=%s", run_id, exc)
