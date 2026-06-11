from __future__ import annotations

import pytest

from app.agents.planner import run_planner


def _tool_names(plan) -> list[str]:
    return [step.tool_name for step in plan.retrieval_plan]


@pytest.mark.asyncio
async def test_trace_keyword_triggers_get_traces():
    plan = await run_planner("connector trace 봐줘", "proj_001")

    assert "get_traces" in _tool_names(plan)
    trace_step = next(step for step in plan.retrieval_plan if step.tool_name == "get_traces")
    assert trace_step.params == {"connector_name": "default-connector"}


@pytest.mark.asyncio
async def test_stacktrace_keyword_triggers_connector_task_trace():
    # #373: 예외/스택트레이스는 분산 trace(get_traces)가 아니라 connector task 예외 조회로 분리.
    plan = await run_planner("sink 스택트레이스 좀 보여줘", "proj_001")

    assert "get_connector_task_trace" in _tool_names(plan)
    assert "get_traces" not in _tool_names(plan)


@pytest.mark.asyncio
async def test_alert_keyword_triggers_get_alerts():
    plan = await run_planner("alert 좀 보여줘", "proj_001")

    assert "get_alerts" in _tool_names(plan)


@pytest.mark.asyncio
async def test_existing_keywords_intact():
    plan = await run_planner("consumer lag 확인해줘", "proj_001")

    assert "get_consumer_lag" in _tool_names(plan)
