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

# #885 재현성: 별칭(gpt-4o)은 provider 가 시간에 따라 다른 가중치로 라우팅할 수 있어
# 같은 판단을 나중에 재현하기 어렵다. run record 에는 별칭 대신 날짜 스냅샷 ID 를 핀고정한다.
MODEL_SNAPSHOT: dict[str, str] = {
    "gpt-4o": "gpt-4o-2024-08-06",
    "gpt-4o-mini": "gpt-4o-mini-2024-07-18",
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


def snapshot_model_id(model_alias: str) -> str:
    """별칭 모델 ID 를 날짜 스냅샷 ID 로 핀고정한다(#885). 매핑이 없으면 원본 유지."""
    return MODEL_SNAPSHOT.get(model_alias, model_alias)


def model_snapshot_for_agent(agent_name: str) -> str:
    """agent 가 실제 사용하는 모델의 날짜 스냅샷 ID."""
    return snapshot_model_id(model_for_agent(agent_name))


def agent_model_snapshot_map() -> dict[str, str]:
    """run record 에 남길 agent → 스냅샷 모델 ID 전체 매핑."""
    return {agent: model_snapshot_for_agent(agent) for agent in AGENT_TIER}
