"""LLM model router — agent 별 모델 tier 매핑."""
from __future__ import annotations

from app.core.config import settings


# 8 LLM agent → 모델 tier 매핑.
# - lightweight (routing/요약): gpt-4o-mini
# - 분석 (Classifier·RCA·Remediation·Verifier): gpt-4o
# 실제 모델 ID 는 settings 가 override 가능 (env: AI_LLM_DEFAULT_MODEL 등)
AGENT_TIER: dict[str, str] = {
    "router": "lightweight",
    "planner": "lightweight",
    "retrieval": "lightweight",
    "classifier": "analysis",
    "rca": "analysis",
    "remediation": "analysis",
    "verifier": "analysis",
    "report": "lightweight",
}


TIER_MODEL_DEFAULT: dict[str, str] = {
    "lightweight": "gpt-4o-mini",
    "analysis": "gpt-4o",
}


def model_for_agent(agent_name: str) -> str:
    """agent 이름 → 실제 모델 ID 변환."""
    override = getattr(settings, "agent_model_override", None) or {}
    if agent_name in override:
        return override[agent_name]

    tier = AGENT_TIER.get(agent_name)
    if tier and tier in TIER_MODEL_DEFAULT:
        return TIER_MODEL_DEFAULT[tier]

    return getattr(settings, "llm_default_model", "gpt-4o-mini")
