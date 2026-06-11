"""#483 — Planner LLM 기반 tool 선택 회귀 테스트.

키워드 비포함 자연어 의도가 LLM 으로 catalog allowlist 안의 read tool 로 라우팅되는지,
allowlist 밖 tool(자유 생성·조치 tool)은 선택되지 않는지, LLM 미가용 시 keyword
fallback 으로 회귀 없이 복구되는지 검증한다.
"""
from __future__ import annotations

import json

import pytest

from app.agents.planner import run_planner
from app.schemas.outputs import PlannerOutput


class _DummyLLMProvider:
    def __init__(self, response: str = "") -> None:
        self.response = response
        self.calls = 0

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        self.calls += 1
        return self.response


def _patch_llm(monkeypatch: pytest.MonkeyPatch, response: str) -> _DummyLLMProvider:
    provider = _DummyLLMProvider(response)
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: provider)
    return provider


def _tools_response(*tools: str) -> str:
    return json.dumps({"tools": list(tools), "reason": "test"})


def _tool_names(plan) -> list[str]:
    return [step.tool_name for step in plan.retrieval_plan]


# ── 키워드 비포함 자연어 → LLM 으로 올바른 read tool 선택 ─────────────────────
@pytest.mark.asyncio
async def test_llm_selects_pipeline_list_for_keywordless_query(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # "리스트/목록" 키워드 없이도 LLM 이 list_project_pipelines 선택.
    _patch_llm(monkeypatch, _tools_response("list_project_pipelines"))

    plan = await run_planner("우리 프로젝트에 뭐가 돌고 있는지 쭉 보여줘", "proj_001")

    assert _tool_names(plan) == ["list_project_pipelines"]


@pytest.mark.asyncio
async def test_llm_selects_metrics_for_keywordless_query(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, _tools_response("get_metrics"))

    plan = await run_planner("요즘 처리 속도가 어떤지 숫자로 보고 싶어", "proj_001")

    assert "get_metrics" in _tool_names(plan)


# ── allowlist 강제: catalog 밖 tool 미선택 ──────────────────────────────────
@pytest.mark.asyncio
async def test_made_up_tool_is_not_selected(monkeypatch: pytest.MonkeyPatch) -> None:
    # LLM 이 catalog 밖 이름을 내면 버리고, 유효 tool 만 남긴다.
    _patch_llm(monkeypatch, _tools_response("teleport_pipeline", "get_alerts"))

    plan = await run_planner("알람 보여줘", "proj_001")

    names = _tool_names(plan)
    assert "teleport_pipeline" not in names
    assert names == ["get_alerts"]


@pytest.mark.asyncio
async def test_mutation_tool_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    # 조치(실행) tool 은 read allowlist 에 없으므로 선택되지 않는다.
    provider = _patch_llm(monkeypatch, _tools_response("restart_connector"))

    plan = await run_planner("로그 좀 보여줘", "proj_001")

    names = _tool_names(plan)
    assert "restart_connector" not in names
    # restart_connector 만 왔으니 유효 tool 0개 → repair 후 keyword fallback(search_logs).
    assert names == ["search_logs"]
    assert provider.calls == 2


# ── fallback: LLM 미가용/무효 → keyword 회귀 없음 ───────────────────────────
@pytest.mark.asyncio
async def test_empty_llm_falls_back_to_keyword(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, "")

    plan = await run_planner("파이프라인 리스트 알려줘", "proj_001")

    names = _tool_names(plan)
    assert "list_project_pipelines" in names
    assert "search_logs" not in names


@pytest.mark.asyncio
async def test_invalid_json_repairs_then_falls_back(monkeypatch: pytest.MonkeyPatch) -> None:
    provider = _patch_llm(monkeypatch, "잘 모르겠어요")

    plan = await run_planner("에러 로그 보여줘", "proj_001")

    assert "search_logs" in _tool_names(plan)  # keyword fallback
    assert provider.calls == 2


@pytest.mark.asyncio
async def test_llm_connector_tool_still_requires_identifier(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # LLM 이 get_consumer_lag 를 골라도 식별자 미해결이면 기존 clarification 경로 유지.
    _patch_llm(monkeypatch, _tools_response("get_consumer_lag"))

    plan = await run_planner("lag 좀 확인해줘", "proj_001")

    assert plan.retrieval_plan == []
    assert plan.clarification_message is not None


@pytest.mark.asyncio
async def test_output_schema_strict(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, _tools_response("get_deployments"))

    plan = await run_planner("최근 배포 뭐 있었는지 보여줘", "proj_001")

    PlannerOutput.model_validate(plan.model_dump())
