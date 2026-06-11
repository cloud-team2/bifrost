"""#504 — 슬래시 명령 결정적 단축 경로 테스트.

`/` 로 시작하는 명령이 LLM 호출 없이 정해진 read-only mode/tool 로 라우팅되는지,
미등록·비슬래시 입력은 None 으로 흘러 기존 LLM(→ keyword) 경로(#483)를 유지하는지
검증한다.
"""
from __future__ import annotations

import json

import pytest

from app.agents.planner import _READ_TOOL_ALLOWLIST, run_planner
from app.agents.router import run_router
from app.agents.slash import SLASH_REGISTRY, resolve_slash
from app.schemas.state import AgentMode


class _DummyLLMProvider:
    def __init__(self, response: str = "") -> None:
        self.response = response
        self.calls: list[list[dict]] = []

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        self.calls.append(messages)
        return self.response


def _patch_llm(monkeypatch: pytest.MonkeyPatch, response: str) -> _DummyLLMProvider:
    provider = _DummyLLMProvider(response)
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: provider)
    return provider


def _tools_response(*tools: str) -> str:
    return json.dumps({"tools": list(tools), "reason": "test"})


def _tool_names(plan) -> list[str]:
    return [step.tool_name for step in plan.retrieval_plan]


# ── resolve_slash 단위 ──────────────────────────────────────────────────────
@pytest.mark.parametrize(
    "message, tool, needs_identifier",
    [
        ("/pipelines", "list_project_pipelines", False),
        ("/connectors", "get_connector_status", True),
        ("/consumer-groups", "get_consumer_lag", True),
        ("/cg", "get_consumer_lag", True),  # alias
        ("/lag", "get_consumer_lag", True),  # alias
        ("/events", "search_logs", False),
        ("/logs", "search_logs", False),  # alias
    ],
)
def test_resolve_slash_maps_command_to_tool(message: str, tool: str, needs_identifier: bool) -> None:
    s = resolve_slash(message)

    assert s is not None
    assert s.mode == AgentMode.SIMPLE_QUERY
    assert s.tool == tool
    assert s.needs_identifier == needs_identifier
    assert s.identifier is None


def test_resolve_slash_parses_identifier_arg() -> None:
    s = resolve_slash("/connectors  my-conn  ")

    assert s is not None
    assert s.command == "/connectors"
    assert s.identifier == "my-conn"


def test_resolve_slash_is_case_insensitive_on_command() -> None:
    s = resolve_slash("/PipeLines")

    assert s is not None
    assert s.tool == "list_project_pipelines"


@pytest.mark.parametrize("message", ["파이프라인 보여줘", "lag 좀 확인해줘", "", "   "])
def test_resolve_slash_returns_none_for_non_slash(message: str) -> None:
    assert resolve_slash(message) is None


@pytest.mark.parametrize("message", ["/unknown", "/restart-connector foo", "/deploy"])
def test_resolve_slash_returns_none_for_unknown_command(message: str) -> None:
    # 미등록 명령·오타 → None(→ LLM 경로로 안전하게 흘림).
    assert resolve_slash(message) is None


def test_registered_tools_are_within_allowlist() -> None:
    for spec in SLASH_REGISTRY.values():
        assert spec.tool in _READ_TOOL_ALLOWLIST


# ── run_router: 슬래시 → 결정적 mode, LLM 호출 0 ────────────────────────────
@pytest.mark.asyncio
async def test_router_slash_is_deterministic_and_skips_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    provider = _patch_llm(monkeypatch, _tools_response("get_alerts"))

    out = await run_router("/pipelines")

    assert out.route_decision.mode == AgentMode.SIMPLE_QUERY
    assert "slash" in out.route_decision.reason
    assert provider.calls == []  # LLM 미호출 증명


@pytest.mark.asyncio
async def test_router_non_slash_still_uses_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    # #483 회귀 가드 — 비슬래시 자유 텍스트는 여전히 LLM 경로를 탄다.
    provider = _patch_llm(
        monkeypatch, json.dumps({"mode": "simple_query", "remediation_requested": False, "reason": "t"})
    )

    out = await run_router("지금 상태 좀 봐줘")

    assert out.route_decision.mode == AgentMode.SIMPLE_QUERY
    assert provider.calls != []  # LLM 호출됨


# ── run_planner: 슬래시 → 결정적 단일 step, LLM 호출 0 ──────────────────────
@pytest.mark.asyncio
async def test_planner_slash_returns_single_step_without_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    provider = _patch_llm(monkeypatch, _tools_response("get_alerts"))

    plan = await run_planner("/pipelines", "proj_001")

    assert _tool_names(plan) == ["list_project_pipelines"]
    assert plan.retrieval_plan[0].step_id == "step_001"
    assert plan.clarification_message is None
    assert provider.calls == []  # LLM 미호출 증명


@pytest.mark.asyncio
async def test_planner_slash_connector_fills_identifier(monkeypatch: pytest.MonkeyPatch) -> None:
    provider = _patch_llm(monkeypatch, "")

    plan = await run_planner("/connectors my-conn", "proj_001")

    assert _tool_names(plan) == ["get_connector_status"]
    assert plan.retrieval_plan[0].params["connector_name"] == "my-conn"
    assert provider.calls == []


@pytest.mark.asyncio
async def test_planner_slash_consumer_group_fills_identifier(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, "")

    plan = await run_planner("/cg my-group", "proj_001")

    assert _tool_names(plan) == ["get_consumer_lag"]
    assert plan.retrieval_plan[0].params["consumer_group"] == "my-group"


@pytest.mark.asyncio
async def test_planner_slash_without_identifier_returns_clarification(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = _patch_llm(monkeypatch, "")

    plan = await run_planner("/connectors", "proj_001")

    assert plan.retrieval_plan == []
    assert plan.clarification_message is not None
    assert provider.calls == []


@pytest.mark.asyncio
async def test_planner_non_slash_still_uses_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    # #483 회귀 가드 — 비슬래시 자유 텍스트는 여전히 LLM 경로를 탄다.
    provider = _patch_llm(monkeypatch, _tools_response("list_project_pipelines"))

    plan = await run_planner("우리 프로젝트에 뭐가 돌고 있는지 보여줘", "proj_001")

    assert _tool_names(plan) == ["list_project_pipelines"]
    assert provider.calls != []  # LLM 호출됨
