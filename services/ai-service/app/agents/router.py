"""Router agent — lightweight LLM 으로 mode 분류, keyword fallback.

설계상 Router 는 LLM agent 다. 사용자 메시지를 mode(simple_query/incident_analysis/
action_execution/approval_decision) + remediation_requested 로 structured 분류한다.
LLM 미가용·파싱 실패 시 keyword 매칭으로 fallback 해 회귀를 막는다(#483).
"""
from __future__ import annotations

from typing import Any

from app.prompts import router as router_prompt
from app.schemas.outputs import RouteDecision, RouterOutput
from app.schemas.state import AgentMode
from app.supervisor.transitions import stages_for_mode

# 명시적 승인/거절 의도 — approval_decision 모드로 라우팅한다.
_APPROVAL_KEYWORDS = {
    "승인", "승인할", "approve", "approved", "approval",
    "거절", "반려", "reject", "rejected", "deny",
}
# 실제 실행 의도(지금 조치를 수행) — action_execution 모드.
_ACTION_EXEC_KEYWORDS = {
    "재시작", "재기동", "restart", "실행해", "실행할", "실행해줘",
    "적용해", "적용", "변경해", "rollback", "롤백", "실행",
}
# 조치 후보 제시 요청(실행 전 제안만) — incident_analysis + remediation_requested.
_REMEDIATION_REQUEST_KEYWORDS = {
    "조치 후보", "조치 방법", "조치 추천", "조치 알려", "어떤 조치",
    "어떻게 해결", "어떻게 조치", "해결 방법", "해결책", "조치해야",
    "remediation", "조치를 추천", "어떤 액션",
}
_INCIDENT_KEYWORDS = {
    "장애", "에러", "오류", "인시던트", "fail", "error", "down",
    "timeout", "타임아웃", "lag", "지연", "중단", "죽",
}

_VALID_MODES = {mode.value for mode in AgentMode}


async def run_router(user_message: str) -> RouterOutput:
    decision = await _llm_route(user_message)
    if decision is None:
        decision = _keyword_route(user_message)

    mode, remediation_requested, reason = decision

    # action_execution/approval_decision은 이전 turn의 분석·조치 State를 재사용한다.
    reuse_existing_analysis = mode in (
        AgentMode.ACTION_EXECUTION,
        AgentMode.APPROVAL_DECISION,
    )

    return RouterOutput(
        route_decision=RouteDecision(
            mode=mode,
            remediation_requested=remediation_requested,
            reuse_existing_analysis=reuse_existing_analysis,
            reason=reason,
            # 실제 transition table과 정합된 stage 흐름을 노출한다(하드코딩 제거).
            required_flow=list(stages_for_mode(mode, remediation_requested)),
        )
    )


def _keyword_route(user_message: str) -> tuple[AgentMode, bool, str]:
    """LLM 미가용 시 fallback — 기존 keyword 매칭 규칙(회귀 보존)."""
    msg = user_message.lower()

    if any(kw in msg for kw in _APPROVAL_KEYWORDS):
        # "승인할게" / "거절" → 이전 run에서 대기 중인 조치 승인/실행 재개
        return AgentMode.APPROVAL_DECISION, False, "approval/reject intent detected"
    if any(kw in msg for kw in _ACTION_EXEC_KEYWORDS):
        # "재시작해줘" 등 실행 동사 → 조치 실행 트랙
        return AgentMode.ACTION_EXECUTION, False, "explicit action execution intent detected"
    if any(kw in msg for kw in _REMEDIATION_REQUEST_KEYWORDS):
        # "조치 후보 보여줘" → 분석 흐름에 remediation/policy_guard 제안 단계 추가(실행 전 정지)
        return AgentMode.INCIDENT_ANALYSIS, True, "remediation candidate request detected"
    if any(kw in msg for kw in _INCIDENT_KEYWORDS):
        return AgentMode.INCIDENT_ANALYSIS, False, "incident analysis intent detected"
    return AgentMode.SIMPLE_QUERY, False, "no incident/action signal — simple query"


async def _llm_route(user_message: str) -> tuple[AgentMode, bool, str] | None:
    """lightweight LLM 으로 mode 분류. 실패 시 None(→ keyword fallback)."""
    from app.llm.structured import complete_structured

    messages = [
        {"role": "system", "content": router_prompt.SYSTEM_PROMPT},
        {"role": "user", "content": router_prompt.build_user_prompt(user_message)},
    ]
    return await complete_structured(
        "router",
        messages,
        _validate_route,
        repair_hint=router_prompt.REPAIR_HINT,
    )


def _validate_route(parsed: dict[str, Any]) -> tuple[AgentMode, bool, str] | None:
    raw_mode = parsed.get("mode")
    if not isinstance(raw_mode, str) or raw_mode not in _VALID_MODES:
        return None
    mode = AgentMode(raw_mode)

    remediation_requested = bool(parsed.get("remediation_requested", False))
    # remediation_requested 는 incident_analysis 에서만 의미가 있다.
    if mode != AgentMode.INCIDENT_ANALYSIS:
        remediation_requested = False

    reason = parsed.get("reason")
    reason = reason if isinstance(reason, str) and reason else "llm router classification"
    return mode, remediation_requested, reason
