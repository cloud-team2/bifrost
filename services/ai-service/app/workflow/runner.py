"""Workflow runner — Supervisor 상태 전이 기반 실행 + SSE 이벤트 발행.

simple_query 경로: planner → retrieval → verifier → report
incident_analysis 경로: correlation → planner → retrieval → classifier → rca → verifier → report
action_execution 경로: policy_guard → approval_gate → change_gate → executor → verifier → report
approval_decision 경로: approval_gate → executor → verifier → report
"""
from __future__ import annotations

import inspect
import json
import logging
import re
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
    ExecutionDepth,
    HistoryPolicy,
    PolicyDecisionType,
    RedactionStatus,
    RiskLevel,
    VerificationStatus,
)
from app.schemas.tools import ToolContext
from app.streaming.event_bus import EventBus
from app.supervisor.graph import get_supervisor
from app.supervisor.transitions import default_depth_for_mode, depth_budget, stages_for_mode
from app.tools.registry import ToolClientRegistry
from app.workflow.action_tools import ACTION_EXECUTION_TOOLS, is_executable_runtime_action_payload
from app.workflow.guards import RunBudgetExceeded
from app.workflow.stages.approval_gate import run_approval_gate
from app.workflow.stages.change_gate import run_change_gate
from app.workflow.stages.correlation import run_correlation
from app.workflow.stages.executor import run_executor
from app.workflow.stages.policy_guard import run_policy_guard
from app.workflow.stages.rollback import run_rollback

logger = logging.getLogger(__name__)
_PUBLIC_FAILURE_MESSAGE = "요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
_ACTION_EXECUTION_TOOLS = ACTION_EXECUTION_TOOLS


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


async def _run_auto_rollback(
    executor_out,
    candidates,
    *,
    run_id: str,
    context,
    registry,
    bus: EventBus,
    event_repo: AnyEventRepo,
    state_repo: Any,
) -> None:
    """#886 실패한 mutation 의 자동 롤백을 실행하고 결과를 이벤트·감사 state 로 남긴다.

    실패 조치가 없으면 아무 것도 하지 않는다(no-op). 롤백 자체가 실패해도 run 흐름은
    막지 않으며, 결과는 항상 append-only state patch 로 감사 추적된다.
    """
    if executor_out is None or not executor_out.execution_results:
        return
    rollback_out = await run_rollback(
        executor_out.execution_results,
        candidates,
        run_id=run_id,
        context=context,
        registry=registry,
    )
    if not rollback_out.rollback_results:
        return

    summary = ", ".join(
        f"{r.original_action_id}:{r.rollback_status.value}" for r in rollback_out.rollback_results
    )
    await _publish(bus, event_repo, run_id, _evt(
        run_id, StreamingEventType.EXECUTION_COMPLETED, "rollback",
        f"조치 실패 자동 롤백: {summary}",
        {"stage": "rollback", "rollback_results": _jsonable(rollback_out.rollback_results)},
    ))
    await _append_state_patch(
        state_repo,
        run_id,
        namespace="actions",
        author="Rollback",
        op="append",
        path="/actions/rollback_results",
        patch={"rollback_results": _jsonable(rollback_out.rollback_results)},
    )


async def _persist_reproducibility(run_repo: Any, state_repo: Any, run_id: str) -> None:
    """#885 재현성 manifest 를 run record + state patch 로 남긴다(best-effort)."""
    try:
        from app.core.reproducibility import build_reproducibility_manifest

        manifest = build_reproducibility_manifest().model_dump(mode="json")
    except Exception as exc:
        logger.warning("reproducibility manifest build failed run=%s error=%s", run_id, exc)
        return
    save = getattr(run_repo, "save_reproducibility", None)
    if save is not None:
        try:
            await save(run_id, manifest)
        except Exception as exc:
            logger.warning("reproducibility persist failed run=%s error=%s", run_id, exc)
    await _append_state_patch(
        state_repo,
        run_id,
        namespace="run",
        author="Supervisor",
        op="version",
        path="/run/reproducibility",
        patch=manifest,
    )


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
    """no-progress 게이트의 결정적 종결 답변(#532).

    단, 수집 근거에 명확한 장애 신호(status=error/FAILED/연결 불가/offline/
    under-replicated/critical>0 등)가 있으면 '정상'으로 끝내지 않고 발견 내용을
    그대로 보고한다(#633). classifier UNKNOWN/verifier loopback 때문에 진짜 장애를
    '이상 없음'으로 결론내던 문제를 폴백 레벨에서 차단한다. 추가 LLM 호출은 없다.
    """
    items = retrieval_out.evidence_items if retrieval_out else []
    summaries = [s for s in (getattr(it, "summary", None) for it in items) if s]
    blob = " ".join(summaries).lower()
    has_error = any(
        sig in blob
        for sig in ("status=error", "failed", "연결 불가", "offline", "under-replicated")
    )
    m = re.search(r"critical:\s*([1-9]\d*)", blob)
    if m:
        has_error = True

    if has_error and summaries:
        lines = "\n".join(f"- {s}" for s in summaries)
        return (
            "수집한 근거에서 장애 신호가 확인되었습니다:\n"
            f"{lines}\n\n"
            "→ 위 신호(상태 error·task 실패·연결 불가·복제 이상·critical 인시던트 등)를 근거로 "
            "원인 조사가 필요합니다."
        )
    if summaries:
        return (
            f"조회한 데이터에서 인시던트나 이상 징후가 발견되지 않았습니다. "
            f"(수집 근거 {len(summaries)}건 검토 결과 정상)"
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


_TARGET_RE = r"[A-Za-z0-9][A-Za-z0-9_.:-]{1,127}"
_TARGET_STOPWORDS = {
    "connector",
    "consumer",
    "group",
    "task",
    "tasks",
    "status",
    "state",
    "failed",
    "failure",
    "error",
    "restart",
    "pause",
    "resume",
    "unknown",
}


def _clean_target_value(value: str | None) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip("`'\".,;:)(")
    if not cleaned:
        return None
    if cleaned.lower() in _TARGET_STOPWORDS:
        return None
    return cleaned


def _action_target_context(user_message: str, retrieval_out) -> str:
    parts = [user_message]
    if retrieval_out is not None:
        for item in getattr(retrieval_out, "evidence_items", []) or []:
            parts.extend([
                getattr(item, "summary", "") or "",
                getattr(item, "store_ref", "") or "",
            ])
    return "\n".join(part for part in parts if part)


def _extract_keyed_target(text: str, key: str) -> str | None:
    patterns = [
        rf"\b{re.escape(key)}\b\s*[:=]\s*['\"]?({_TARGET_RE})",
        rf"['\"]{re.escape(key)}['\"]\s*:\s*['\"]({_TARGET_RE})['\"]",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.IGNORECASE)
        if match:
            value = _clean_target_value(match.group(1))
            if value:
                return value
    return None


def _extract_connector_target(text: str) -> str | None:
    keyed = _extract_keyed_target(text, "connector_name") or _extract_keyed_target(text, "connectorName")
    if keyed:
        return keyed

    patterns = [
        rf"(?:connector|커넥터)\s*(?:name|이름|명)?\s*[:=]?\s*`?({_TARGET_RE})",
        rf"\b({_TARGET_RE})\s*(?:connector|커넥터)\b",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.IGNORECASE)
        if match:
            value = _clean_target_value(match.group(1))
            if value:
                return value

    consumer_group = _extract_consumer_group_target(text)
    if consumer_group and consumer_group.lower().startswith("connect-"):
        return consumer_group[len("connect-"):]
    return None


def _extract_consumer_group_target(text: str) -> str | None:
    keyed = (
        _extract_keyed_target(text, "consumer_group")
        or _extract_keyed_target(text, "consumerGroup")
    )
    if keyed:
        return keyed

    group_match = re.search(rf"\b(connect-{_TARGET_RE})\b", text, flags=re.IGNORECASE)
    if group_match:
        return _clean_target_value(group_match.group(1))

    patterns = [
        rf"(?:consumer\s*group|consumer-group|컨슈머\s*그룹|group|그룹)\s*(?:name|이름|명)?\s*[:=]?\s*`?({_TARGET_RE})",
        rf"\b({_TARGET_RE})\s*(?:consumer\s*group|consumer-group|컨슈머\s*그룹)\b",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.IGNORECASE)
        if match:
            value = _clean_target_value(match.group(1))
            if value:
                return value
    return None


def _infer_tool_params(tool_name: str | None, text: str) -> dict[str, Any] | None:
    if tool_name in {"restart_connector", "pause_connector", "resume_connector"}:
        connector_name = _extract_connector_target(text)
        return {"connector_name": connector_name} if connector_name else None
    if tool_name == "restart_consumer_group":
        consumer_group = _extract_consumer_group_target(text)
        if not consumer_group:
            connector_name = _extract_connector_target(text)
            consumer_group = f"connect-{connector_name}" if connector_name else None
        return {"consumer_group": consumer_group} if consumer_group else None
    return None


def _with_inferred_tool_params(
    remediation_out: RemediationOutput,
    *,
    user_message: str,
    retrieval_out,
) -> RemediationOutput:
    context = _action_target_context(user_message, retrieval_out)
    candidates: list[ActionCandidateOutput] = []
    changed = False
    for candidate in remediation_out.action_candidates:
        if candidate.tool_params or candidate.action_type != ActionType.RUNTIME_TOOL:
            candidates.append(candidate)
            continue
        tool_params = _infer_tool_params(candidate.tool_name, context)
        if tool_params is None:
            candidates.append(candidate)
            continue
        candidates.append(candidate.model_copy(update={"tool_params": tool_params}))
        changed = True
    if not changed:
        return remediation_out
    return remediation_out.model_copy(update={"action_candidates": candidates})


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


# --- #712 대화 메모리: thread history 주입 ---

_HISTORY_TURNS = 10            # 직전 메시지 최대 개수(~5턴)
_HISTORY_CHAR_CAP = 1500      # turn당 길이 컷(토큰 폭증 방지)


def _format_history(messages: list) -> str:
    """이전 대화 메시지들을 LLM 컨텍스트용 텍스트 블록으로 만든다. 비면 빈 문자열."""
    lines: list[str] = []
    for m in messages:
        role = getattr(m, "role", None)
        content = (getattr(m, "content", "") or "").strip()
        if not content or role not in ("user", "assistant"):
            continue
        if len(content) > _HISTORY_CHAR_CAP:
            content = content[:_HISTORY_CHAR_CAP] + "…"
        who = "사용자" if role == "user" else "어시스턴트"
        lines.append(f"{who}: {content}")
    return "\n".join(lines)


def _contextualize(user_message: str, history_block: str) -> str:
    """직전 대화 맥락을 현재 질문 앞에 명시 구분해 붙인다. history 없으면 원문 그대로."""
    if not history_block:
        return user_message
    return (
        "[이전 대화 맥락 — 같은 스레드의 직전 대화이니 후속 질문 해석에 참고]\n"
        f"{history_block}\n\n[현재 질문]\n{user_message}"
    )


async def _load_history_block(message_repo, thread_id: str | None) -> str:
    """thread의 이전 메시지를 로드해 컨텍스트 블록으로 만든다. 실패는 빈 문자열로 흡수."""
    if not message_repo or not thread_id:
        return ""
    try:
        prior = await message_repo.list_by_thread(thread_id, limit=_HISTORY_TURNS)
    except Exception as exc:
        logger.warning("conversation history load failed thread=%s error=%s", thread_id, exc)
        return ""
    return _format_history(prior)


async def _append_message(message_repo, thread_id, role, content, *, project_id, run_id) -> None:
    """대화 turn 저장. 메모리 기능은 보조라 실패해도 run 흐름을 막지 않는다."""
    if not message_repo or not thread_id or not content:
        return
    try:
        await message_repo.append(
            thread_id, role, content, project_id=project_id, run_id=run_id
        )
    except Exception as exc:
        logger.warning("conversation message persist failed thread=%s error=%s", thread_id, exc)


async def _list_run_events(event_repo, run_id: str):
    res = event_repo.get_after(run_id, None)
    return await res if inspect.isawaitable(res) else res


async def _persist_run_transcript(message_repo, thread_id, *, run_id, project_id) -> None:
    """#874 런이 만든 응답(구조화 tool 패널 + 최종 답변)을 thread에 저장한다.

    run_workflow의 finally에서 호출돼 예외·중단(승인 거절 등)에도 보장된다. 복원 시
    role='tool'(JSON {tool_name, params, result})은 toolPanel로, 최종 답변은 assistant 텍스트로 재현된다.
    사용자에게 패널로 보였던 구조화 tool(result가 있는 TOOL_CALL_COMPLETED)만 저장한다.
    """
    if not message_repo or not thread_id:
        return
    try:
        events = await _list_run_events(get_event_repo(), run_id)
    except Exception as exc:
        logger.warning("run transcript events lookup failed run=%s error=%s", run_id, exc)
        events = []

    terminal_message = ""
    for ev in events:
        payload = getattr(ev, "payload", None) or {}
        if ev.type == StreamingEventType.RUN_COMPLETED and ev.message:
            terminal_message = ev.message
        if (
            ev.type == StreamingEventType.TOOL_CALL_COMPLETED
            and payload.get("result") is not None
            and payload.get("tool")
        ):
            content = json.dumps(
                {
                    "tool_name": payload.get("tool"),
                    "params": payload.get("params") or {},
                    "result": payload.get("result"),
                },
                ensure_ascii=False,
                default=str,
            )
            await _append_message(
                message_repo, thread_id, "tool", content, project_id=project_id, run_id=run_id
            )

    answer = ""
    try:
        snapshot = await get_report_repo().get_latest(run_id, verified_only=False)
        answer = (snapshot.body.get("answer") if snapshot and snapshot.body else "") or ""
    except Exception as exc:
        logger.warning("assistant reply lookup failed run=%s error=%s", run_id, exc)
    answer = answer.strip() or terminal_message.strip()
    await _append_message(
        message_repo, thread_id, "assistant", answer, project_id=project_id, run_id=run_id
    )


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
    thread_id: str | None = None,
    message_repo: Any = None,
    display_message: str | None = None,
) -> None:
    """에이전트 run 진입점. 루트 trace span(#372)으로 감싸 run 전체(+Spring 호출)를 한 trace 로 묶는다.

    BackgroundTask 로 실행되어 요청 핸들러 span 밖이므로, 여기서 루트 span 을 직접 연다.

    #712 대화 메모리: thread_id가 있으면 직전 대화를 컨텍스트로 주입하고, 현재 질문과 run이
    만든 최종 answer를 thread에 turn으로 저장한다(멀티턴 연속성).
    """
    with run_span(
        run_id=run_id,
        project_id=project_id,
        mode=requested_mode,
        incident_id=requested_incident_id,
    ):
        # 이전 대화 로드 → 현재 질문 저장 → 컨텍스트 주입된 메시지로 run 실행.
        # #870 thread에는 사용자에게 보이는 친근한 텍스트(display_message)를 저장하고,
        # LLM에는 구조화 원문(user_message)을 주입한다(조치/재분석은 둘이 다름).
        history_block = await _load_history_block(message_repo, thread_id)
        persisted_user = (display_message or "").strip() or user_message
        await _append_message(
            message_repo, thread_id, "user", persisted_user,
            project_id=project_id, run_id=run_id,
        )
        effective_message = _contextualize(user_message, history_block)

        try:
            await _run_workflow_impl(
                run_id=run_id,
                user_message=effective_message,
                raw_user_message=user_message,
                project_id=project_id,
                bus=bus,
                run_repo=run_repo,
                registry=registry,
                requested_mode=requested_mode,
                requested_incident_id=requested_incident_id,
                requested_remediation_requested=requested_remediation_requested,
                requested_action_candidate=requested_action_candidate,
            )
        finally:
            # #874 런 응답(구조화 tool 패널 + 최종 답변)을 thread에 저장 — 예외·중단에도 보장.
            # 복원 시 사용자 요청 + tool 패널 + agent 답변이 그대로 재현된다.
            await _persist_run_transcript(
                message_repo, thread_id, run_id=run_id, project_id=project_id,
            )


async def _run_workflow_impl(
    *,
    run_id: str,
    user_message: str,
    raw_user_message: str | None = None,
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
        # #882 실행 깊이 확정. Router 가 simple_query 로 분류한 그대로면 Router 의 depth 를
        # 쓰고, mode 가 외부 요청/incident 컨텍스트로 바뀌었으면 그 mode 의 기본 depth 로
        # 재산정한다. remediation 이 뒤늦게 켜진 incident 는 remediation_planning 으로 승격.
        router_depth = router_out.route_decision.execution_depth
        if mode == router_out.route_decision.mode and router_depth is not None:
            execution_depth = router_depth
        else:
            execution_depth = default_depth_for_mode(mode, remediation_requested)
        if (
            mode == AgentMode.INCIDENT_ANALYSIS
            and remediation_requested
            and execution_depth == ExecutionDepth.INCIDENT_DIAGNOSIS
        ):
            execution_depth = ExecutionDepth.REMEDIATION_PLANNING
        max_tool_calls, allow_react_loop, react_max_steps, history_policy = depth_budget(
            execution_depth
        )
        # #604: 전체 stage 흐름은 이 시점에 확정된다. FE가 진행 단계 분모를
        # 처음부터 고정할 수 있도록 required_flow를 payload로 노출한다.
        required_flow = list(stages_for_mode(mode, remediation_requested, execution_depth))
        await _publish(bus, event_repo, run_id, _evt(
            run_id, StreamingEventType.AGENT_COMPLETED, "router", f"mode: {mode.value}",
            {
                "mode": mode.value,
                "required_flow": required_flow,
                "total_stages": len(required_flow),
                "execution_depth": execution_depth.value,
                "max_tool_calls": max_tool_calls,
                "allow_react_loop": allow_react_loop,
            },
        ))
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
            execution_depth=execution_depth,
        )

        # #885 run 단위 재현성: 당시 모델 스냅샷·프롬프트·카탈로그·코드 버전을 고정 저장한다.
        # run record 테이블(run_reproducibility)과 append-only state patch 양쪽에 남겨,
        # 나중에 이 run 의 입력·버전·후보 랭킹을 그대로 재구성할 수 있게 한다.
        await _persist_reproducibility(run_repo, state_repo, run_id)

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
                    # #882 history input filter: history_policy=none 인 단순 조회는 이전 대화
                    # 맥락을 Planner 에 주입하지 않는다(지난 장애 키워드가 단순 질의를 오염시키는 것 차단).
                    planner_message = (
                        raw_user_message
                        if history_policy == HistoryPolicy.NONE and raw_user_message is not None
                        else user_message
                    )
                    planner_out = await planner_agent.run_planner(
                        planner_message,
                        project_id,
                        registry=registry,
                        tool_context=planner_context,
                    )
                    # (#692) ReAct 루프가 있으면 커넥터 이름을 list_connectors/topology 체이닝으로
                    # 알아낼 수 있으므로, '이름을 알려달라'며 단축하지 않고 retrieval(루프)로 넘긴다.
                    # 루프 불가(LLM 미연결)일 때만 clarification 으로 종료한다.
                    if planner_out.clarification_message and not get_llm_provider().supports_tools():
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
                        # (#692) ReAct 루프가 전체 도구결과로 합성한 답이 있으면 그걸 최종답으로 쓴다.
                        # generic 폴백("장애 신호 확인...")보다 실제 근본원인 서사가 담긴다.
                        loop_answer = getattr(retrieval_out, "answer", None) if retrieval_out else None
                        answer = loop_answer or _no_incident_answer(retrieval_out)
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
                        # #882 Router 가 정한 실행 깊이 예산 — 단순 조회는 tool 수/ReAct 를 제한한다.
                        max_tool_calls=max_tool_calls,
                        allow_react_loop=allow_react_loop,
                        react_max_steps=react_max_steps,
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
                    # #670: 검증 전 preview를 사전 노출하지 않는다 — verifier 통과 후에만 공개.

                case "remediation":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "remediation", "조치 후보를 생성합니다"))
                    remediation_out = await remediation_agent.run_remediation(rca_out)
                    remediation_out = _with_inferred_tool_params(
                        remediation_out,
                        user_message=user_message,
                        retrieval_out=retrieval_out,
                    )
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
                    approval_out = await run_approval_gate(decisions, run_id, project_id=project_id)
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
                    if approval_out.approval_requests:
                        await _append_state_patch(
                            state_repo,
                            run_id,
                            namespace="actions",
                            author="HumanApprovalGate",
                            op="append",
                            path="/actions/approval_requests",
                            patch={"approval_requests": _jsonable(approval_out.approval_requests)},
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
                        # #886 조치 전 상태 스냅샷을 남겨 실패 시 자동 롤백 기준으로 쓴다.
                        capture_pre_change_snapshot=True,
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
                    # #886 조치가 성공 조건을 충족하지 못하면(FAILED/BLOCKED) 사람 개입 없이
                    # inverse 조치로 자동 롤백한다. high-risk 원조치의 롤백은 승인 대상으로 남긴다.
                    await _run_auto_rollback(
                        executor_out,
                        ready_candidates,
                        run_id=run_id,
                        context=exec_context,
                        registry=registry,
                        bus=bus,
                        event_repo=event_repo,
                        state_repo=state_repo,
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
                    # #670: verifier 통과 시에만 검증된 preview를 emit한다.
                    from app.schemas.state import VerificationStatus as _VS
                    if (first_result is not None
                            and first_result.status == _VS.PASS
                            and rca_out is not None
                            and rca_out.root_cause_candidates):
                        top = rca_out.root_cause_candidates[0]
                        await _publish(bus, event_repo, run_id, _evt(
                            run_id, StreamingEventType.REPORT_PREVIEW_AVAILABLE, "verifier",
                            f"원인 후보: {top.root_cause_id} (confidence: {top.confidence:.0%})",
                            {"root_cause_id": top.root_cause_id, "confidence": top.confidence, "verified": True},
                        ))
                    # #453: Verifier fail/needs_revision이면 책임 Agent로 loopback을
                    # 등록한다. 예산 초과 시 다음 advance에서 RunBudgetExceeded로 종료.
                    record_verifier = getattr(supervisor, "record_verifier_result", None)
                    if record_verifier is not None and first_result is not None:
                        record_verifier(run_id, first_result.status, first_result.next_agent)

                case "report":
                    await _publish(bus, event_repo, run_id,
                                   _evt(run_id, StreamingEventType.AGENT_STARTED, "report", "답변을 생성합니다"))
                    loop_answer = getattr(retrieval_out, "answer", None) if retrieval_out else None
                    if mode in (AgentMode.ACTION_EXECUTION, AgentMode.APPROVAL_DECISION):
                        answer = _action_execution_answer(executor_out, verifier_out)
                    elif mode == AgentMode.SIMPLE_QUERY and loop_answer:
                        # (#633) ReAct 루프가 전체 도구 결과로 합성한 답을 그대로 쓴다(sql_read 행값 등
                        # 실데이터를 살리기 위함). report LLM 재합성은 evidence 요약만 봐서 데이터를 누락한다.
                        answer = loop_answer
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
                    # #882 단순 조회(direct/single/bounded lookup)는 검증 agent 를 호출하지 않고
                    # 곧장 답변을 확정한다(설계 §6.4.2: schema/citation 검증으로 대체). 운영 변경·
                    # RCA 결론처럼 검증 가치가 큰 출력에서만 verifier loopback 을 유지한다.
                    skip_verifier = (
                        mode == AgentMode.SIMPLE_QUERY
                        and execution_depth in (
                            ExecutionDepth.DIRECT_ANSWER,
                            ExecutionDepth.SINGLE_LOOKUP,
                            ExecutionDepth.BOUNDED_LOOKUP,
                        )
                    )
                    if not skip_verifier:
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
        candidates = _restore_executable_candidates(candidates_raw)
        if candidates:
            remediation_out = RemediationOutput(action_candidates=candidates)

    policy_out: PolicyGuardOutput | None = None
    if policy_raw:
        decisions = _restore_executable_policy_decisions(policy_raw)
        if decisions:
            policy_out = PolicyGuardOutput(policy_decisions=decisions)

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


def _restore_executable_candidates(raw: list) -> list[ActionCandidateOutput]:
    candidates: list[ActionCandidateOutput] = []
    for item in raw:
        if not isinstance(item, dict) or not is_executable_runtime_action_payload(item):
            continue
        try:
            candidates.append(ActionCandidateOutput(**item))
        except Exception as exc:
            logger.warning("state restore: candidate skipped action=%s error=%s", item.get("action_id"), exc)
    return candidates


def _restore_executable_policy_decisions(raw: list) -> list[PolicyDecisionOutput]:
    decisions: list[PolicyDecisionOutput] = []
    for item in raw:
        if not isinstance(item, dict) or not is_executable_runtime_action_payload(item):
            continue
        try:
            decisions.append(PolicyDecisionOutput(**item))
        except Exception as exc:
            logger.warning("state restore: policy decision skipped action=%s error=%s", item.get("action_id"), exc)
    return decisions


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
    if rca_out is not None and rca_out.root_cause_candidates:
        body["root_cause"] = {
            "root_cause_id": root_cause_id,
            "confidence": confidence,
        }
        body["root_cause_candidates"] = [
            candidate.model_dump(mode="json")
            for candidate in rca_out.root_cause_candidates
        ]
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
