"""Router agent — keyword-based mode judgment (no LLM)."""
from __future__ import annotations

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


async def run_router(user_message: str) -> RouterOutput:
    msg = user_message.lower()

    remediation_requested = False

    if any(kw in msg for kw in _APPROVAL_KEYWORDS):
        # "승인할게" / "거절" → 이전 run에서 대기 중인 조치 승인/실행 재개
        mode = AgentMode.APPROVAL_DECISION
        reason = "approval/reject intent detected"
    elif any(kw in msg for kw in _ACTION_EXEC_KEYWORDS):
        # "재시작해줘" 등 실행 동사 → 조치 실행 트랙
        mode = AgentMode.ACTION_EXECUTION
        reason = "explicit action execution intent detected"
    elif any(kw in msg for kw in _REMEDIATION_REQUEST_KEYWORDS):
        # "조치 후보 보여줘" → 분석 흐름에 remediation/policy_guard 제안 단계 추가(실행 전 정지)
        mode = AgentMode.INCIDENT_ANALYSIS
        remediation_requested = True
        reason = "remediation candidate request detected"
    elif any(kw in msg for kw in _INCIDENT_KEYWORDS):
        mode = AgentMode.INCIDENT_ANALYSIS
        reason = "incident analysis intent detected"
    else:
        mode = AgentMode.SIMPLE_QUERY
        reason = "no incident/action signal — simple query"

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
