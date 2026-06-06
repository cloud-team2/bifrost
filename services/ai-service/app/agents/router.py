"""Router agent — keyword-based mode judgment (no LLM)."""
from __future__ import annotations

from app.schemas.outputs import RouteDecision, RouterOutput
from app.schemas.state import AgentMode

_INCIDENT_KEYWORDS = {
    "장애", "에러", "오류", "인시던트", "fail", "error", "down",
    "timeout", "타임아웃", "lag", "지연", "중단", "죽",
}
_ACTION_KEYWORDS = {
    "재시작", "restart", "실행해", "적용해", "변경해줘",
    "rollback", "롤백", "실행", "조치",
}


async def run_router(user_message: str) -> RouterOutput:
    msg = user_message.lower()

    if any(kw in msg for kw in _ACTION_KEYWORDS):
        mode = AgentMode.ACTION_EXECUTION
    elif any(kw in msg for kw in _INCIDENT_KEYWORDS):
        mode = AgentMode.INCIDENT_ANALYSIS
    else:
        mode = AgentMode.SIMPLE_QUERY

    return RouterOutput(
        route_decision=RouteDecision(
            mode=mode,
            remediation_requested=False,
            reuse_existing_analysis=False,
            reason="keyword-based routing",
            required_flow=["planner", "retrieval", "verifier", "report"],
        )
    )
