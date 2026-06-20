"""#882 자연어 질의 실행 깊이 제어 — 회귀 테스트.

대표 자연어 질의별로 Router 가 정하는 실행 깊이(execution_depth)·tool 예산과,
depth 별 stage 경로(단순 조회는 classifier/rca/verifier 미호출), retrieval 의
tool 호출 수 상한·ReAct 게이팅을 assertion 한다.
"""
from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from app.agents.agentic import AgenticResult
from app.agents.retrieval import run_retrieval
from app.agents.router import run_router
from app.persistence.event_repository import InMemoryEventRepository
from app.schemas.outputs import PlannerOutput, RetrievalPlanStep
from app.schemas.state import AgentMode, ExecutionDepth, HistoryPolicy, RiskLevel
from app.schemas.tools import ToolContext, ToolResult, ToolStatus
from app.streaming.event_bus import EventBus
from app.supervisor.transitions import (
    default_depth_for_mode,
    depth_budget,
    stages_for_mode,
)


# ── Router: 대표 질의 → 실행 깊이/예산 ────────────────────────────────────────
@pytest.mark.parametrize(
    "message, mode, depth, max_calls, react",
    [
        ("DLQ가 뭐야?", AgentMode.SIMPLE_QUERY, ExecutionDepth.DIRECT_ANSWER, 0, False),
        ("파이프라인 목록 보여줘", AgentMode.SIMPLE_QUERY, ExecutionDepth.SINGLE_LOOKUP, 1, False),
        ("현재 상태 요약해줘", AgentMode.SIMPLE_QUERY, ExecutionDepth.BOUNDED_LOOKUP, 2, False),
        ("주문 파이프라인 장애 원인 분석해줘", AgentMode.INCIDENT_ANALYSIS, ExecutionDepth.INCIDENT_DIAGNOSIS, 6, True),
        ("조치 방법 알려줘", AgentMode.INCIDENT_ANALYSIS, ExecutionDepth.REMEDIATION_PLANNING, 6, True),
        ("커넥터 재시작해줘", AgentMode.ACTION_EXECUTION, ExecutionDepth.ACTION_EXECUTION, 0, False),
    ],
)
@pytest.mark.asyncio
async def test_router_assigns_execution_depth_and_budget(
    message: str,
    mode: AgentMode,
    depth: ExecutionDepth,
    max_calls: int,
    react: bool,
) -> None:
    out = await run_router(message)
    rd = out.route_decision

    assert rd.mode == mode
    assert rd.execution_depth == depth
    assert rd.max_tool_calls == max_calls
    assert rd.allow_react_loop is react


# ── 단순 조회 depth 는 classifier/rca/verifier 를 stage 경로에 포함하지 않는다 ──
@pytest.mark.parametrize(
    "depth",
    [ExecutionDepth.DIRECT_ANSWER, ExecutionDepth.SINGLE_LOOKUP, ExecutionDepth.BOUNDED_LOOKUP],
)
def test_lookup_depths_skip_classifier_rca_verifier(depth: ExecutionDepth) -> None:
    stages = stages_for_mode(AgentMode.SIMPLE_QUERY, False, depth)
    assert "classifier" not in stages
    assert "rca" not in stages
    assert "verifier" not in stages
    assert stages == ("planner", "retrieval", "report")


def test_incident_diagnosis_runs_full_analysis_chain() -> None:
    stages = stages_for_mode(
        AgentMode.INCIDENT_ANALYSIS, False, ExecutionDepth.INCIDENT_DIAGNOSIS
    )
    for required in ("classifier", "rca", "verifier"):
        assert required in stages


def test_remediation_planning_depth_adds_remediation_suffix() -> None:
    stages = stages_for_mode(
        AgentMode.INCIDENT_ANALYSIS, False, ExecutionDepth.REMEDIATION_PLANNING
    )
    assert "remediation" in stages
    assert "policy_guard" in stages


def test_depth_budget_and_defaults() -> None:
    # 단순 조회는 ReAct 를 끄고 tool 수를 좁힌다.
    assert depth_budget(ExecutionDepth.DIRECT_ANSWER) == (0, False, 0, HistoryPolicy.NONE)
    assert depth_budget(ExecutionDepth.SINGLE_LOOKUP)[0] == 1
    assert depth_budget(ExecutionDepth.BOUNDED_LOOKUP)[0] == 2
    # 인시던트/조치 분석은 ReAct 를 켠다.
    assert depth_budget(ExecutionDepth.INCIDENT_DIAGNOSIS)[1] is True
    assert default_depth_for_mode(AgentMode.SIMPLE_QUERY) == ExecutionDepth.BOUNDED_LOOKUP
    assert (
        default_depth_for_mode(AgentMode.INCIDENT_ANALYSIS, remediation_requested=True)
        == ExecutionDepth.REMEDIATION_PLANNING
    )


# ── retrieval: max_tool_calls 가 운영 tool 호출 수를 제한한다 ──────────────────
def _context() -> ToolContext:
    return ToolContext(
        run_id="r1", step_id="s1", agent_name="retrieval", project_id="p1", request_id="req1"
    )


def _plan_with_n_tools(n: int) -> PlannerOutput:
    tools = ["list_pipelines", "get_metrics", "get_consumer_lag", "list_connectors", "get_alerts"]
    return PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id=f"s{i}",
                tool_name=tools[i],
                params={},
                purpose="test",
                depends_on=[],
                plan_hash=f"hash{i}",
            )
            for i in range(n)
        ]
    )


def _ok_registry() -> AsyncMock:
    registry = AsyncMock()
    registry.call_tool.return_value = ToolResult(
        tool_name="x",
        status=ToolStatus.SUCCESS,
        risk=RiskLevel.READ_ONLY,
        summary="data",
        evidence_ids=[],
    )
    return registry


@pytest.mark.parametrize("max_calls, expected_calls", [(0, 0), (1, 1), (2, 2), (None, 3)])
@pytest.mark.asyncio
async def test_retrieval_caps_operational_tool_calls(
    max_calls: int | None, expected_calls: int
) -> None:
    registry = _ok_registry()
    bus = EventBus()
    bus.publish = AsyncMock()

    await run_retrieval(
        "r1",
        _plan_with_n_tools(3),
        _context(),
        registry,
        bus,
        InMemoryEventRepository(),
        mode=AgentMode.SIMPLE_QUERY,
        max_tool_calls=max_calls,
        allow_react_loop=False,
    )

    assert registry.call_tool.call_count == expected_calls


# ── retrieval: ReAct 루프는 allow_react_loop 일 때만 돈다 ──────────────────────
class _ToolProvider:
    def supports_tools(self) -> bool:
        return True


@pytest.mark.asyncio
async def test_react_loop_disabled_for_simple_lookup(monkeypatch: pytest.MonkeyPatch) -> None:
    loop_mock = AsyncMock(return_value=AgenticResult(answer="", calls=[], used_llm=False))
    monkeypatch.setattr("app.agents.retrieval.get_llm_provider", lambda: _ToolProvider())
    monkeypatch.setattr("app.agents.retrieval.run_tool_loop", loop_mock)

    bus = EventBus()
    bus.publish = AsyncMock()
    await run_retrieval(
        "r1",
        _plan_with_n_tools(1),
        _context(),
        _ok_registry(),
        bus,
        InMemoryEventRepository(),
        mode=AgentMode.SIMPLE_QUERY,
        max_tool_calls=1,
        allow_react_loop=False,
    )
    loop_mock.assert_not_called()


@pytest.mark.asyncio
async def test_react_loop_enabled_for_incident(monkeypatch: pytest.MonkeyPatch) -> None:
    loop_mock = AsyncMock(return_value=AgenticResult(answer="", calls=[], used_llm=False))
    monkeypatch.setattr("app.agents.retrieval.get_llm_provider", lambda: _ToolProvider())
    monkeypatch.setattr("app.agents.retrieval.run_tool_loop", loop_mock)

    bus = EventBus()
    bus.publish = AsyncMock()
    await run_retrieval(
        "r1",
        _plan_with_n_tools(1),
        _context(),
        _ok_registry(),
        bus,
        InMemoryEventRepository(),
        mode=AgentMode.INCIDENT_ANALYSIS,
        max_tool_calls=6,
        allow_react_loop=True,
        react_max_steps=6,
    )
    loop_mock.assert_called_once()
