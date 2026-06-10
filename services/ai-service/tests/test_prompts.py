"""Prompt module + InMemoryVectorStore health smoke tests (#345 cleanup)."""
from __future__ import annotations

import asyncio
import importlib

import pytest

from app.llm.model_router import AGENT_TIER, TIER_MODEL_DEFAULT, model_for_agent

# 본문 보유 4 모듈만 검증 (빈 4 모듈은 별도 sub 이슈)
PROMPT_MODULES_WITH_BODY = ("classifier", "rca", "remediation", "verifier")


@pytest.mark.parametrize("agent_name", PROMPT_MODULES_WITH_BODY)
def test_prompt_module_has_system_prompt(agent_name: str) -> None:
    """본문 보유 4 모듈은 비어있지 않은 SYSTEM_PROMPT 보유."""
    module = importlib.import_module(f"app.prompts.{agent_name}")
    assert hasattr(module, "SYSTEM_PROMPT"), f"{agent_name} missing SYSTEM_PROMPT"
    assert isinstance(module.SYSTEM_PROMPT, str)
    assert module.SYSTEM_PROMPT.strip() != "", f"{agent_name} SYSTEM_PROMPT empty"


@pytest.mark.parametrize("agent_name", PROMPT_MODULES_WITH_BODY)
def test_prompt_module_has_build_user_prompt(agent_name: str) -> None:
    """본문 보유 4 모듈은 callable build_user_prompt 보유."""
    module = importlib.import_module(f"app.prompts.{agent_name}")
    assert hasattr(module, "build_user_prompt"), f"{agent_name} missing build_user_prompt"
    assert callable(module.build_user_prompt)


def test_prompts_init_includes_original_filled_modules() -> None:
    """#345 4 모듈 export 회귀: 본문 보유 4 모듈은 계속 export."""
    from app import prompts

    assert set(PROMPT_MODULES_WITH_BODY).issubset(set(prompts.__all__))


def test_inmemory_vector_store_health_ok() -> None:
    """InMemoryVectorStore.health() returns True (테스트 환경 일관성)."""
    from app.knowledge.vector_store import InMemoryVectorStore

    store = InMemoryVectorStore()
    result = asyncio.run(store.health())
    assert result is True


@pytest.mark.parametrize("agent_name", sorted(AGENT_TIER.keys()))
def test_agent_has_prompt_module_with_system_prompt(agent_name: str) -> None:
    """AGENT_TIER 의 모든 agent 가 자기 prompt 모듈 + non-empty SYSTEM_PROMPT 보유."""
    module = importlib.import_module(f"app.prompts.{agent_name}")
    assert hasattr(module, "SYSTEM_PROMPT"), f"{agent_name} missing SYSTEM_PROMPT"
    assert isinstance(module.SYSTEM_PROMPT, str)
    assert module.SYSTEM_PROMPT.strip() != "", f"{agent_name} SYSTEM_PROMPT empty"


@pytest.mark.parametrize("agent_name", sorted(AGENT_TIER.keys()))
def test_agent_prompt_has_build_user_prompt(agent_name: str) -> None:
    """AGENT_TIER 의 모든 agent 가 callable build_user_prompt 보유."""
    module = importlib.import_module(f"app.prompts.{agent_name}")
    assert hasattr(module, "build_user_prompt"), f"{agent_name} missing build_user_prompt"
    assert callable(module.build_user_prompt)


def test_prompts_init_exports_all_agent_tier_keys() -> None:
    """app.prompts.__all__ ↔ AGENT_TIER 키셋 정합 (8 모듈)."""
    from app import prompts

    expected = set(AGENT_TIER.keys())
    actual = set(prompts.__all__)
    assert actual == expected, f"prompts.__all__ {actual} != AGENT_TIER keys {expected}"


def test_model_for_agent_returns_known_tier_model() -> None:
    """model_for_agent 가 모든 AGENT_TIER 키에 대해 TIER_MODEL_DEFAULT 값 또는 settings override 반환."""
    assert set(TIER_MODEL_DEFAULT) == {"lightweight", "analysis"}
    for agent_name in AGENT_TIER:
        model = model_for_agent(agent_name)
        assert isinstance(model, str) and model, f"{agent_name} returned invalid model: {model}"


def test_model_for_agent_unknown_agent_falls_back() -> None:
    """알 수 없는 agent_name → fallback 모델 (예외 미발생)."""
    result = model_for_agent("nonexistent_agent_xyz")
    assert isinstance(result, str) and result
