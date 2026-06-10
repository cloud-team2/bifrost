"""Prompt module + InMemoryVectorStore health smoke tests (#345 cleanup)."""
from __future__ import annotations

import asyncio
import importlib

import pytest

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


def test_prompts_init_exports_filled_modules_only() -> None:
    """app.prompts.__all__ 은 본문 보유 4 모듈만 export (빈 4 모듈은 별도 sub 이슈)."""
    from app import prompts

    expected = set(PROMPT_MODULES_WITH_BODY)
    actual = set(prompts.__all__)
    assert actual == expected, f"prompts.__all__ {actual} != filled set {expected}"


def test_inmemory_vector_store_health_ok() -> None:
    """InMemoryVectorStore.health() returns True (테스트 환경 일관성)."""
    from app.knowledge.vector_store import InMemoryVectorStore

    store = InMemoryVectorStore()
    result = asyncio.run(store.health())
    assert result is True
