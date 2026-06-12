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
from app.core.tracing import run_span, set_current_run_mode
from app.llm.provider import get_llm_provider
from app.persistence.change_ticket_repository import STATUS_VERIFIED
from app.persistence.event_repository import AnyEventRepo, append_event, get_event_repo
from app.persistence.report_repository import get_report_repo
from app.persistence.run_repository import AnyRunRepo
from app.persistence.state_repository import get_state_repo
from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.outputs import (
    ActionCandidateOutput,
    RetrievalOutput,
    PolicyDecisionOutput,
    PolicyGuardOutput,
    RemediationOutput,
)
from app.schemas.state import (
    ActionCandidate,
    ActionStatus,
    ActionType,
    AgentMode,
    EvidenceItem,
    EvidenceType,
    PolicyDecisionType,
    RedactionStatus,
    RiskLevel,
    VerificationStatus,
)
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
_ACTION_EXECUTION_TOOLS = {
    "pause_connector",
    "resume_connector",
}


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


def _coerce_requested_action_candidate(
    raw: dict[str, Any] | ActionCandidateOutput | None,
    registry: ToolClientRegistry,
) -> ActionCandidateOutput | None:
    """Create an executable candidate from the selected report action.

    Reported remediation risk stays in the report. Execution uses the registered
    tool risk, with medium mutation tools promoted to human approval so the Run
    path does not stall on change-management UI that this flow does not own.
    """
    if raw is None:
        return None
    candidate = raw if isinstance(raw, ActionCandidateOutput) else ActionCandidateOutput.model_validate(raw)
    if candidate.action_type != ActionType.RUNTIME_TOOL:
        return candidate.model_copy(update={"risk": RiskLevel.FORBIDDEN})
    if not candidate.tool_name:
        return candidate.model_copy(update={"risk": RiskLevel.FORBIDDEN})

    definition = registry.get_definition(candidate.tool_name)
    if definition is None:
        return candidate.model_copy(update={"risk": RiskLevel.FORBIDDEN})
    if definition.name not in _ACTION_EXECUTION_TOOLS:
        return candidate.model_copy(update={"risk": RiskLevel.FORBIDDEN})
    try:
        definition.validate_params(candidate.tool_params or {})
    except Exception:
        return candidate.model_copy(update={"risk": RiskLevel.FORBIDDEN})
    execution_risk = RiskLevel.HIGH if definition.risk == RiskLevel.MEDIUM else definition.risk
    return candidate.model_copy(update={"risk": execution_risk})


def _precondition_message(candidate: ActionCandidateOutput) -> str:
    bits = [f"사전 조건 검증: risk={candidate.risk.value}"]
    if candidate.estimated_duration:
        bits.append(f"예상 소요={candidate.estimated_duration}")
    if candidate.risk.value not in {"read_only", "low"}:
        bits.append("운영 트래픽에 일시적 영향이 있을 수 있어 승인/정책 게이트를 확인합니다")
    return " · ".join(bits)


def _execution_event_message(executor_out) -> str:
    if executor_out is None or not executor_out.execution_results:
        return "실행된 backend mutation이 없습니다"
    completed = sum(1 for result in executor_out.execution_results if result.status == ActionStatus.COMPLETED)
    failed = sum(1 for result in executor_out.execution_results if result.status == ActionStatus.FAILED)
    blocked = sum(1 for result in executor_out.execution_results if result.status == ActionStatus.BLOCKED)
    return f"backend mutation 결과: completed={completed}, failed={failed}, blocked={blocked}"


def _action_execution_answer(executor_out, verifier_out) -> str:
    lines = ["조치 실행 결과"]
    if executor_out is None or not executor_out.execution_results:
        lines.append("- 승인되었거나 실행 가능한 조치가 없어 backend mutation을 호출하지 않았습니다.")
    else:
        for result in executor_out.execution_results:
            lines.append(
                f"- {result.action_id}: {result.status.value} ({result.tool_name}) — {result.summary}"
            )

    first_verification = (
        verifier_out.verification_results[0]
        if verifier_out is not None and verifier_out.verification_results
        else None
    )
    if first_verification is not None:
        lines.append(f"사후 검증: {first_verification.status.value} — {first_verification.reason}")
    return "\n".join(lines)


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


def _classifier_scope_unclear(classifier_out) -> bool:
    """Classifier의 scope_unclear 신호 매핑(#476, §3 'scope 불명확').

    기존 ClassifierOutput에는 명시적 scope_unclear 값이 없다. 가장 자연스러운
    매핑은 "알려진 incident type을 하나도 확정하지 못해 UNKNOWN_NEEDS_MORE_EVIDENCE
    후보만 남은 경우"다 — 이는 분류기가 scope/유형을 가르지 못했다는 뜻이며,
    설계의 'scope 불명확 → Planner 재수집'과 의미가 일치한다.
    """
    if classifier_out is None:
        return False
    incident_types = classifier_out.classification.incident_types
    if not incident_types:
        return True
    return all(t.type == classifier_agent.UNKNOWN_INCIDENT_TYPE for t in incident_types)


def _no_incident_answer(retrieval_out) -> str:
    """클린/정상 결과(인시던트 0건)일 때의 결정적 종결 답변(#532).

    no-progress 게이트가 같은 plan을 재수집해도 진전이 없다고 판단했을 때,
    추가 LLM 호출 없이 곧장 내보낼 사용자 답변이다. 수집 근거 건수만 반영한다.
    retrieval_out이 None이어도 안전하다.
    """
    n = len(retrieval_out.evidence_items) if retrieval_out else 0
    if n > 0:
        return (
            f"조회한 데이터에서 인시던트나 이상 징후가 발견되지 않았습니다. "
            f"(수집 근거 {n}건 검토 결과 정상)"
        )
    return "조회한 데이터에서 인시던트나 이상 징후가 발견되지 않았습니다. (정상)"


def _no_action_answer(mode) -> str:
    """실행할 조치가 하나도 없을 때의 결정적 종결 답변(#553).

    action_execution/approval_decision에서 ready_candidates(승인/검증된 조치)가
    비어 있으면 Executor가 빈 결과만 내고, Verifier가 needs_revision으로 Executor에
    loopback시켜 gap_loops 예산 초과("실행 예산 초과")로 run이 실패한다. 결정적
    Executor를 같은 빈 후보로 재실행해봐야 진전이 없으므로, 추가 LLM 호출 없이
    곧장 정상 종결할 사용자 답변을 돌려준다.
    """
    if mode == AgentMode.APPROVAL_DECISION:
        return "승인 대기 중인 조치가 없습니다."
    return "요청하신 작업에 대해 실행할 승인된 조치가 없습니다."


def _policy_revise_action(policy_out) -> bool:
    """Policy Guard의 revise_action 신호 매핑(#476, §3 revise_action).

    PolicyDecisionType에는 revise_action 값이 없다. 가장 자연스러운 매핑은
    decision==DENY(status BLOCKED)다 — 제안된 조치가 정책상 불가하므로 Remediation이
    더 안전한 대안을 재생성해야 한다는 의미이고, 설계의 'revise_action → Remediation'과
    부합한다.
    """
    if policy_out is None:
        return False
    return any(d.decision == PolicyDecisionType.DENY for d in policy_out.policy_decisions)


def _evidence_patch(evidence) -> dict[str, Any]:
    return {
        "evidence_id": evidence.evidence_id,
        "type": _jsonable(evidence.type),
        "store_ref": evidence.store_ref,
        "summary": evidence.summary,
        "redaction_status": _jsonable(evidence.redaction_status),
    }


def _string_or_none(value: object) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _bool_or_false(value: object) -> bool:
    return value if isinstance(value, bool) else False


def _estimate_tokens(*texts: str | None) -> int:
    """LLM usage가 없을 때를 대비한 거친 토큰 추정(#481).

    실제 토크나이저 없이 char/4 근사(영문 기준 통상치)를 쓴다. 예산 guard 집행용
    누적치를 위한 것이라 정밀도는 중요치 않다.
    """
    return sum(len(t) for t in texts if t) // 4


def _agent_mode_or_none(value: object) -> AgentMode | None:
    if isinstance(value, AgentMode):
        return value
    if isinstance(value, str):
        try:
            return AgentMode(value)
        except ValueError:
            return None
    return None


async def run_workflow(
    *,
    run_id: str,
    user_message: str,
    project_id: str,
    bus: EventBus,
    run_repo: AnyRunRepo,
    registry: ToolClientRegistry,
    requested_mode: str | None = None,
    requested_incident_id: str | None = None,
    requested_remediation_requested: bool | None = None,
    requested_action_candidate: dict[str, Any] | ActionCandidateOutput | None = None,
) -> None:
    """에이전트 run 진입점. 루트 trace span(#372)으로 감싸 run 전체(+Spring 호출)를 한 trace 로 묶는다.

    BackgroundTask 로 실행되어 요청 핸들러 span 밖이므로, 여기서 루트 span 을 직접 연다.
    """
    with run_span(
        run_id=run_id,
        project_id=project_id,
        mode=requested_mode,
        incident_id=requested_incident_id,
    ):
        await _run_workflow_impl(
            run_id=run_id,
            user_message=user_message,
            project_id=project_id,
            bus=bus,
            run_repo=run_repo,
            registry=registry,
            requested_mode=requested_mode,
            requested_incident_id=requested_incident_id,
            requested_remediation_requested=requested_remediation_requested,
            requested_action_candidate=requested_action_candidate,
        )


async def _run_workflow_impl(
    *,
    run_id: str,
    user_message: str,
    project_id: str,
    bus: EventBus,
    run_repo: AnyRunRepo,
    registry: ToolClientRegistry,
    requested_mode: str | None = None,
    requested_incident_id: str | None = None,
    requested_remediation_requested: bool | None = None,
    requested_action_candidate: dict[str, Any] | ActionCandidateOutput | None = None,
) -> None:
    event_repo = get_event_repo()
    state_repo = get_state_repo()
    supervisor = get_supervisor()
    answer: str | None = None  # report 단계 전 budget 초과 대비
    keep_stream_open = False
    run_record = await run_repo.get(run_id)
    persisted_incident_id = _string_or_none(getattr(run_record, "incident_id", None)) if run_record else None
    stored_remediation_requested = (
        _bool_or_false(getattr(run_record, "remediation_requested", False)) if run_record else False
    )

    try:
        await _publish(bus, event_repo, run_id,
                       _evt(run_id, StreamingEventType.RUN_STARTED, None, "분석을 시작합니다"))

        # ── Router: Supervisor 외부에서 mode 결정 ─────────────────────────────
        await run_repo.update_status(run_id, "running", "router")
        await _publish(bus, event_repo, run_id,
                       _evt(run_id, StreamingEventType.AGENT_STARTED, "router", "질문 유형을 파악합니다"))
        router_out = await router_agent.run_router(user_message)
        mode = _agent_mode_or_none(requested_mode) or router_out.route_decision.mode
        requested_incident = _string_or_none(requested_incident_id)
        router_incident = _string_or_none(getattr(router_out.route_decision, "incident_id", None))
        persisted_incident_id = requested_incident or persisted_incident_id or router_incident
        if persisted_incident_id and mode == AgentMode.SIMPLE_QUERY:
            mode = AgentMode.INCIDENT_ANALYSIS
        remediation_requested = (
            _bool_or_false(requested_remediation_requested)
            or stored_remediation_requested
            or _bool_or_false(router_out.route_decision.remediation_requested)
        )
        set_current_run_mode(mode.value)  # router 결정 mode 를 run span 에 기록(#372)
        await _publish(bus, event_repo, run_id,
                       _evt(run_id, StreamingEventType.AGENT_COMPLETED, "router", f"mode: {mode.value}"))
        if persisted_incident_id:
            await _append_state_patch(
                state_repo,
                run_id,
                namespace="incident",
                author="Router",
                op="append",
                path="/incident/incident_id",
                patch={"incident_id": persisted_incident_id, "mode": mode.value},
            )

        # ── Supervisor 초기화 ──────────────────────────────────────────────────
        supervisor.start_run(
            run_id, mode,
            incident_id=persisted_incident_id,
            remediation_requested=remediation_requested,
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
        # #532: 같은 plan을 재수집해도 진전이 없는 클린/정상 결과를 감지하기 위한
        # 로컬 누적기. RunPlanState.executed_plan_hashes는 persistence 전용이고
        # in-memory supervisor state로 되읽지 않으므로, 런너 루프 스코프에서 직접 모은다.
        executed_plan_hashes: set[str] = set()

        # ── State 재사용 ────────────────────────────────────────────────────────
        # action_execution/approval_decision은 policy_guard/approval_gate부터 시작해
        # 같은 turn 안에서는 조치 후보를 만들지 않는다. 같은 run(멀티턴, #454)의 이전
        # remediation/policy State를 복원하고, FE가 메시지마다 새 run을 생성해 후속
        # turn이 다른 run_id로 들어오면 같은 incident_id의 직전 run에서 복원한다(#479).
        if mode in (AgentMode.ACTION_EXECUTION, AgentMode.APPROVAL_DECISION):
            selected_candidate = _coerce_requested_action_candidate(requested_action_candidate, registry)
            if selected_candidate is not None:
                remediation_out = RemediationOutput(action_candidates=[selected_candidate])
                await _append_state_patch(
                    state_repo,
                    run_id,
                    namespace="actions",
                    author="RunRequest",
                    op="append",
                    path="/actions/candidates",
                    patch={"candidates": _jsonable(remediation_out.action_candidates)},
                )
                await _publish(bus, event_repo, run_id, _evt(
                    run_id, StreamingEventType.PARTIAL_RESULT, "router",
                    "인시던트 컨텍스트와 선택 조치를 수집했습니다",
                    {
                        "stage": "action_context",
                        "incident_id": persisted_incident_id,
                        "action_id": selected_candidate.action_id,
                        "tool_name": selected_candidate.tool_name,
                        "risk": selected_candidate.risk.value,
                        "estimated_duration": selected_candidate.estimated_duration,
                    },
                ))
            else:
                restored_rem, restored_policy = await _restore_action_state(
                    state_repo,
                    run_id,
                    incident_id=persisted_incident_id,
                    run_repo=run_repo,
                )
                if restored_rem is not None:
                    remediation_out = restored_rem
                if restored_policy is not None:
                    policy_out = restored_policy
                restored_count = (
                    len(restored_rem.action_candidates) if restored_rem else
                    len(restored_policy.policy_decisions) if restored_policy else 0
                )
                if restored_count:
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.PARTIAL_RESULT, "router",
                        f"이전 분석의 조치 후보 {restored_count}건을 재사용합니다",
                        {"stage": "state_reuse", "restored_candidates": restored_count},
                    ))

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
                    planner_context = ToolContext(
                        run_id=run_id,
                        step_id=str(uuid4()),
                        agent_name="planner",
                        project_id=project_id,
                        request_id=str(uuid4()),
                    )
                    planner_out = await planner_agent.run_planner(
                        user_message,
                        project_id,
                        registry=registry,
                        tool_context=planner_context,
                    )
                    if planner_out.clarification_message:
                        answer = planner_out.clarification_message
                        retrieval_out = RetrievalOutput(
                            evidence_items=[
                                EvidenceItem(
                                    evidence_id=str(uuid4()),
                                    type=EvidenceType.TOOL_RESULT,
                                    store_ref=f"planner://{run_id}/identifier-required",
                                    summary=answer,
                                    redaction_status=RedactionStatus.REDACTED,
                                    collected_by="planner",
                                    collected_at=datetime.now(timezone.utc),
                                )
                            ]
                        )
                        await _append_state_patch(
                            state_repo,
                            run_id,
                            namespace="report",
                            author="Planner",
                            op="append",
                            path="/report/draft",
                            patch={"draft": {"answer": answer, "reason": "identifier_required"}},
                        )
                        await _persist_report_snapshot(
                            run_id=run_id,
                            answer=answer,
                            mode=mode,
                            retrieval_out=retrieval_out,
                            rca_out=None,
                            verifier_out=None,
                            incident_id=persisted_incident_id,
                        )
                        await run_repo.update_status(run_id, "completed", None)
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id,
                            StreamingEventType.PARTIAL_RESULT,
                            "planner",
                            answer,
                            {"answer": answer, "stage": "planner", "reason": "identifier_required"},
                        ))
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id,
                            StreamingEventType.RUN_COMPLETED,
                            "planner",
                            "분석이 완료되었습니다",
                            {"answer": answer},
                        ))
                        return
                    # #532: no-progress 게이트. 직전까지 실행한 plan_hash 집합을
                    # 완전히 포함(subset)하는 동일 plan을 다시 받았다면, 재수집해도
                    # 같은 빈/정상 근거만 나와 진전이 없다는 뜻이다. classifier
                    # scope_unclear/verifier gap loopback이 같은 결정적 plan을
                    # 무한 재수집하다 예산 초과로 실패하는 대신, 클린 결과로 정상 종결한다.
                    this_plan_hashes = {s.plan_hash for s in planner_out.retrieval_plan}
                    no_progress = bool(this_plan_hashes) and this_plan_hashes <= executed_plan_hashes
                    executed_plan_hashes |= this_plan_hashes  # no_progress 계산 후 갱신
                    if no_progress and remediation_requested and rca_out is None:
                        # #592: 조치 후보를 요청한 run은 같은 plan 재수집으로 진전이
                        # 없어도 클린 종료하지 않는다. 재수집을 생략하고 지금까지의
                        # evidence로 rca→remediation→policy_guard를 이어가 조치 후보를
                        # 제시한다(FR-022, 실행 전 정지). rca_out이 이미 있으면(verifier
                        # loopback 재진입) 기존 클린 종료를 유지해 무한 재생성을 막는다.
                        supervisor.force_next_stage(run_id, "rca")
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id, StreamingEventType.PARTIAL_RESULT, "planner",
                            "추가 수집 없이 기존 근거로 조치 후보 생성을 진행합니다",
                            {"stage": "planner", "reason": "no_progress_remediation_continue"},
                        ))
                        continue
                    if no_progress:
                        answer = _no_incident_answer(retrieval_out)
                        await _append_state_patch(
                            state_repo,
                            run_id,
                            namespace="report",
                            author="Planner",
                            op="append",
                            path="/report/draft",
                            patch={"draft": {"answer": answer, "reason": "no_progress_clean_result"}},
                        )
                        await _persist_report_snapshot(
                            run_id=run_id,
                            answer=answer,
                            mode=mode,
                            retrieval_out=retrieval_out or RetrievalOutput(evidence_items=[]),
                            rca_out=None,
                            verifier_out=None,
                            incident_id=persisted_incident_id,
                        )
                        await run_repo.update_status(run_id, "completed", None)
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id,
                            StreamingEventType.PARTIAL_RESULT,
                            "planner",
                            answer,
                            {"answer": answer, "stage": "planner", "reason": "no_progress_clean_result"},
                        ))
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id,
                            StreamingEventType.RUN_COMPLETED,
                            "planner",
                            "분석이 완료되었습니다",
                            {"answer": answer},
                        ))
                        return
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
                    # #476: scope_unclear면 Planner로 loopback 등록(scope_loops 예산).
                    # 예산 초과 시 다음 advance에서 RunBudgetExceeded("scope_loops").
                    record_classifier = getattr(supervisor, "record_classifier_result", None)
                    if record_classifier is not None:
                        record_classifier(
                            run_id, scope_unclear=_classifier_scope_unclear(classifier_out)
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
                    for candidate in candidates:
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id, StreamingEventType.PARTIAL_RESULT, "policy_guard",
                            _precondition_message(candidate),
                            {
                                "stage": "precondition",
                                "action_id": candidate.action_id,
                                "risk": candidate.risk.value,
                                "estimated_duration": candidate.estimated_duration,
                                "tool_name": candidate.tool_name,
                            },
                        ))
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
                    # #476: revise_action(DENY)이면 Remediation으로 loopback 등록
                    # (revise_action_loops 예산). Remediation↔Policy Guard 순환은
                    # incident_analysis remediation 흐름에만 존재하므로 그 mode로 한정한다
                    # (action_execution엔 remediation stage가 없어 무한·오류 방지).
                    # 예산 초과 시 다음 advance에서 RunBudgetExceeded("revise_action_loops").
                    record_policy = getattr(supervisor, "record_policy_guard_result", None)
                    if record_policy is not None and mode == AgentMode.INCIDENT_ANALYSIS:
                        record_policy(
                            run_id, revise_action=_policy_revise_action(policy_out)
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
                    if approval_out.run_status == "waiting_for_approval":
                        keep_stream_open = True
                        return

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
                        keep_stream_open = True
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
                    change_records = change_out.change_management_records if change_out else []
                    change_ready_ids = [
                        record.action_id
                        for record in change_records
                        if record.status == STATUS_VERIFIED
                    ]
                    # per-action governance 식별자 매핑 — 승인된 mutation 실행 시 Spring
                    # governance gate 로 X-Approval-Id / X-Change-Ticket-Id 전달. (#475)
                    # auto_/PENDING_ 센티넬은 실제 승인/티켓이 아니므로 제외(헤더 미전송).
                    approval_by_action = {
                        a.action_id: a.approval_id
                        for a in approved
                        if a.approval_id and not a.approval_id.startswith("auto_")
                    }
                    change_ticket_by_action = {
                        record.action_id: record.change_ticket_id
                        for record in change_records
                        if record.status == STATUS_VERIFIED
                        and record.change_ticket_id
                        and not record.change_ticket_id.startswith("PENDING_")
                    }
                    ready_candidates = [
                        candidate
                        for action_id in _dedupe_action_ids(
                            [a.action_id for a in approved] + change_ready_ids
                        )
                        if (candidate := _ready_action_candidate(action_id, remediation_out, decisions)) is not None
                    ]
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.EXECUTION_STARTED, "executor",
                        f"실행 가능한 조치 {len(ready_candidates)}건을 호출합니다",
                        {
                            "stage": "execution",
                            "actions": [_jsonable(candidate) for candidate in ready_candidates],
                        },
                    ))
                    executor_out = await run_executor(
                        ready_candidates,
                        run_id=run_id,
                        context=exec_context,
                        registry=registry,
                        approval_by_action=approval_by_action,
                        change_ticket_by_action=change_ticket_by_action,
                    )
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.AGENT_COMPLETED, "executor",
                        f"실행 {len(executor_out.execution_results)}건",
                    ))
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id, StreamingEventType.EXECUTION_COMPLETED, "executor",
                        _execution_event_message(executor_out),
                        {
                            "stage": "execution",
                            "execution_results": _jsonable(executor_out.execution_results),
                        },
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
                    # #553: 실행할 조치가 없으면(ready_candidates 비어 있음) Verifier로
                    # 진행하지 않고 정상 종결한다. 결정적 Executor는 같은 빈 후보로
                    # 재실행해도 빈 결과만 내므로, Verifier needs_revision → Executor
                    # loopback이 gap_loops 예산을 소진해 "실행 예산 초과"로 실패하는
                    # 구조적 무진전 루프가 된다. #532 클린 결과 종결과 같은 패턴으로,
                    # executor↔verifier 루프에 진입하기 전에 곧장 completed로 끝낸다.
                    if not ready_candidates:
                        answer = _no_action_answer(mode)
                        await _append_state_patch(
                            state_repo,
                            run_id,
                            namespace="report",
                            author="Executor",
                            op="append",
                            path="/report/draft",
                            patch={"draft": {"answer": answer, "reason": "no_action_to_execute"}},
                        )
                        await _persist_report_snapshot(
                            run_id=run_id,
                            answer=answer,
                            mode=mode,
                            retrieval_out=retrieval_out or RetrievalOutput(evidence_items=[]),
                            rca_out=None,
                            verifier_out=None,
                            incident_id=persisted_incident_id,
                        )
                        await run_repo.update_status(run_id, "completed", None)
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id,
                            StreamingEventType.PARTIAL_RESULT,
                            "executor",
                            answer,
                            {"answer": answer, "stage": "executor", "reason": "no_action_to_execute"},
                        ))
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id,
                            StreamingEventType.RUN_COMPLETED,
                            "executor",
                            "분석이 완료되었습니다",
                            {"answer": answer},
                        ))
                        return

                case "verifier":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "verifier", "결과를 검증합니다"))
                    verifier_out = await verifier_agent.run_verifier(
                        mode,
                        rca_out=rca_out,
                        retrieval_out=retrieval_out,
                        classifier_out=classifier_out,
                        executor_out=executor_out,
                    )
                    first_result = (
                        verifier_out.verification_results[0]
                        if verifier_out.verification_results
                        else None
                    )
                    v_status = first_result.status.value if first_result else "pass"
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
                    # #453: Verifier fail/needs_revision이면 책임 Agent로 loopback을
                    # 등록한다. 예산 초과 시 다음 advance에서 RunBudgetExceeded로 종료.
                    record_verifier = getattr(supervisor, "record_verifier_result", None)
                    if record_verifier is not None and first_result is not None:
                        record_verifier(run_id, first_result.status, first_result.next_agent)

                case "report":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "report", "답변을 생성합니다"))
                    if mode in (AgentMode.ACTION_EXECUTION, AgentMode.APPROVAL_DECISION):
                        answer = _action_execution_answer(executor_out, verifier_out)
                    else:
                        llm = get_llm_provider()
                        answer = await report_agent.run_report(
                            user_message, retrieval_out, mode, llm,
                            rca_out=rca_out, classifier_out=classifier_out,
                        )
                        # #481: Report LLM 호출을 token/call 예산에 계측한다. 예산 초과 시
                        # 다음 advance(loopback 포함)의 check_all_global이 run을 안전 종료한다.
                        record_usage = getattr(supervisor, "record_llm_usage", None)
                        if record_usage is not None:
                            record_usage(run_id, calls=1, tokens=_estimate_tokens(user_message, answer))
                    verifier_out = await verifier_agent.run_verifier(
                        mode,
                        rca_out=rca_out,
                        retrieval_out=retrieval_out,
                        classifier_out=classifier_out,
                        executor_out=executor_out,
                        report_body=answer,
                    )
                    first_result = (
                        verifier_out.verification_results[0]
                        if verifier_out.verification_results
                        else None
                    )
                    v_status = first_result.status.value if first_result else "pass"
                    await _publish(bus, event_repo, run_id, _evt(
                        run_id,
                        StreamingEventType.VERIFICATION_COMPLETED,
                        "verifier",
                        f"report 검증: {v_status}",
                    ))
                    await _append_state_patch(
                        state_repo,
                        run_id,
                        namespace="verification",
                        author="Verifier",
                        op="append",
                        path="/verification/verification_results",
                        patch={"verification_results": _jsonable(verifier_out.verification_results)},
                    )
                    record_verifier = getattr(supervisor, "record_verifier_result", None)
                    if record_verifier is not None and first_result is not None:
                        record_verifier(run_id, first_result.status, first_result.next_agent)
                    if first_result is not None and first_result.status != VerificationStatus.PASS:
                        continue
                    await _persist_report_snapshot(
                        run_id=run_id,
                        answer=answer,
                        mode=mode,
                        retrieval_out=retrieval_out,
                        rca_out=rca_out,
                        verifier_out=verifier_out,
                        incident_id=persisted_incident_id,
                        remediation_out=remediation_out,
                        policy_out=policy_out,
                        approval_out=approval_out,
                        executor_out=executor_out,
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
        if not keep_stream_open:
            await bus.close_run(run_id)


async def _restore_action_state_from_run(
    state_repo: Any,
    run_id: str,
) -> tuple[RemediationOutput | None, PolicyGuardOutput | None]:
    """단일 run_id의 append-only State patch에서 조치 후보·정책 결정을 복원한다."""
    try:
        patches = await state_repo.get_patches(run_id)
    except Exception as exc:
        logger.warning("state restore: get_patches failed run=%s error=%s", run_id, exc)
        return None, None

    candidates_raw: list | None = None
    policy_raw: list | None = None
    for patch in patches:
        if getattr(patch, "namespace", None) != "actions":
            continue
        body = getattr(patch, "patch", {}) or {}
        if patch.path == "/actions/candidates" and body.get("candidates"):
            candidates_raw = body["candidates"]  # 최신 patch가 우선(seq 순회)
        elif patch.path == "/actions/policy_decisions" and body.get("policy_decisions"):
            policy_raw = body["policy_decisions"]

    remediation_out: RemediationOutput | None = None
    if candidates_raw:
        try:
            remediation_out = RemediationOutput(
                action_candidates=[ActionCandidateOutput(**c) for c in candidates_raw]
            )
        except Exception as exc:
            logger.warning("state restore: candidate rebuild failed run=%s error=%s", run_id, exc)

    policy_out: PolicyGuardOutput | None = None
    if policy_raw:
        try:
            policy_out = PolicyGuardOutput(
                policy_decisions=[PolicyDecisionOutput(**d) for d in policy_raw]
            )
        except Exception as exc:
            logger.warning("state restore: policy rebuild failed run=%s error=%s", run_id, exc)

    return remediation_out, policy_out


async def _sibling_run_ids(
    run_repo: Any,
    incident_id: str,
    exclude_run_id: str,
) -> list[str]:
    """같은 incident_id의 직전 run_id를 최신순으로 반환한다(#479).

    run_repo가 list_run_ids_by_incident를 제공하지 않거나(AsyncMock 등) 비정상
    값을 돌려주면 빈 list로 흡수해 복원이 조용히 no-op이 되게 한다.
    """
    fn = getattr(run_repo, "list_run_ids_by_incident", None)
    if fn is None:
        return []
    try:
        result = await fn(incident_id, exclude_run_id=exclude_run_id)
    except Exception as exc:
        logger.warning(
            "state restore: sibling lookup failed incident=%s error=%s", incident_id, exc
        )
        return []
    if not isinstance(result, list):
        return []
    return [r for r in result if isinstance(r, str)]


async def _restore_action_state(
    state_repo: Any,
    run_id: str,
    *,
    incident_id: str | None = None,
    run_repo: Any = None,
) -> tuple[RemediationOutput | None, PolicyGuardOutput | None]:
    """조치 후보·정책 결정 State를 복원한다(intra-run + cross-turn, #454·#479).

    action_execution/approval_decision turn은 remediation 단계를 다시 돌지 않으므로,
    `/actions/candidates`·`/actions/policy_decisions` patch를 읽어 Policy
    Guard/Executor에 공급한다.

    먼저 같은 run_id의 patch를 본다(#454, 같은 run 멀티턴). FE가 메시지마다 새
    run을 생성하면 후속 turn(새 run_id)에는 후보 patch가 없으므로, incident_id가
    있으면 같은 incident의 직전 run들을 최신순으로 조회해 처음으로 후보·정책이
    잡히는 run에서 복원한다(#479, cross-turn 멀티 run). 복원 실패는 빈 결과로 흡수한다.
    """
    remediation_out, policy_out = await _restore_action_state_from_run(state_repo, run_id)
    if remediation_out is not None or policy_out is not None:
        return remediation_out, policy_out

    if not incident_id or run_repo is None:
        return remediation_out, policy_out

    for sibling_run_id in await _sibling_run_ids(run_repo, incident_id, run_id):
        sib_rem, sib_policy = await _restore_action_state_from_run(state_repo, sibling_run_id)
        if sib_rem is not None or sib_policy is not None:
            logger.info(
                "state restore: reused candidates from run=%s incident=%s for run=%s",
                sibling_run_id, incident_id, run_id,
            )
            return sib_rem, sib_policy

    return None, None


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
                if candidate.action_type == ActionType.RUNTIME_TOOL:
                    if not candidate.tool_params or candidate.tool_name not in _ACTION_EXECUTION_TOOLS:
                        return None
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
                    tool_params=candidate.tool_params,
                    status=ActionStatus.READY,
                )

    for decision in policy_decisions:
        if decision.action_id == action_id:
            tool_params = getattr(decision, "tool_params", None)
            if decision.action_type == ActionType.RUNTIME_TOOL:
                if not tool_params or decision.tool_name not in _ACTION_EXECUTION_TOOLS:
                    return None
            return ActionCandidate(
                action_id=decision.action_id,
                action_type=decision.action_type,
                action_name=decision.action_id,
                risk=decision.risk,
                reason=decision.reason,
                tool_name=decision.tool_name,
                tool_params=tool_params,
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
    incident_id: str | None = None,
    remediation_out=None,
    policy_out=None,
    approval_out=None,
    executor_out=None,
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
    if remediation_out is not None:
        body["action_candidates"] = [
            candidate.model_dump(mode="json")
            for candidate in remediation_out.action_candidates
        ]
    if policy_out is not None:
        body["policy_decisions"] = [
            decision.model_dump(mode="json")
            for decision in policy_out.policy_decisions
        ]
    if approval_out is not None:
        body["approved_actions"] = [
            action.model_dump(mode="json")
            for action in approval_out.approved_actions
        ]
    if executor_out is not None:
        body["execution_results"] = [
            result.model_dump(mode="json")
            for result in executor_out.execution_results
        ]

    try:
        await get_report_repo().create(
            run_id,
            body,
            incident_id=incident_id,
            root_cause_id=root_cause_id,
            confidence=confidence,
            verified=verified,
        )
    except Exception as exc:  # report cache failure must not hide the final answer
        logger.warning("report snapshot persistence failed: run_id=%s error=%s", run_id, exc)
