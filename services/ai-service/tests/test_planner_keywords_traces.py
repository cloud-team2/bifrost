from __future__ import annotations

import pytest

from app.agents.planner import run_planner
from app.schemas.state import RiskLevel
from app.schemas.tools import ToolContext, ToolResult, ToolStatus


def _tool_names(plan) -> list[str]:
    return [step.tool_name for step in plan.retrieval_plan]


@pytest.mark.asyncio
async def test_trace_keyword_without_connector_name_does_not_use_placeholder():
    plan = await run_planner("connector trace 봐줘", "proj_001")

    names = _tool_names(plan)
    assert "get_traces" not in names
    assert "list_connectors" in names
    assert all(step.params.get("connector_name") != "default-connector" for step in plan.retrieval_plan)


@pytest.mark.asyncio
async def test_trace_keyword_with_prefix_connector_name_triggers_get_traces():
    plan = await run_planner("connector orders-sink trace 봐줘", "proj_001")

    assert "get_traces" in _tool_names(plan)
    trace_step = next(step for step in plan.retrieval_plan if step.tool_name == "get_traces")
    assert trace_step.params == {"connector_name": "orders-sink"}


@pytest.mark.asyncio
async def test_trace_keyword_with_suffix_connector_name_triggers_get_traces():
    plan = await run_planner("orders-sink connector trace 봐줘", "proj_001")

    assert "get_traces" in _tool_names(plan)
    trace_step = next(step for step in plan.retrieval_plan if step.tool_name == "get_traces")
    assert trace_step.params == {"connector_name": "orders-sink"}


@pytest.mark.asyncio
async def test_stacktrace_keyword_triggers_connector_task_trace():
    # #373: 예외/스택트레이스는 분산 trace(get_traces)가 아니라 connector task 예외 조회로 분리.
    plan = await run_planner("connector orders-sink 스택트레이스 좀 보여줘", "proj_001")

    assert "get_connector_task_trace" in _tool_names(plan)
    assert "get_traces" not in _tool_names(plan)
    trace_step = next(step for step in plan.retrieval_plan if step.tool_name == "get_connector_task_trace")
    assert trace_step.params == {"connector_name": "orders-sink"}


@pytest.mark.asyncio
async def test_alert_keyword_triggers_get_alerts():
    plan = await run_planner("alert 좀 보여줘", "proj_001")

    assert "get_alerts" in _tool_names(plan)


@pytest.mark.asyncio
async def test_existing_keywords_intact():
    plan = await run_planner("orders-sink consumer lag 확인해줘", "proj_001")

    assert "get_consumer_lag" in _tool_names(plan)
    lag_step = next(step for step in plan.retrieval_plan if step.tool_name == "get_consumer_lag")
    assert lag_step.params == {"consumer_group": "connect-orders-sink"}


@pytest.mark.asyncio
async def test_connect_consumer_group_used_as_is_for_lag():
    plan = await run_planner("connect-orders-sink lag 확인해줘", "proj_001")

    lag_step = next(step for step in plan.retrieval_plan if step.tool_name == "get_consumer_lag")
    assert lag_step.params == {"consumer_group": "connect-orders-sink"}


@pytest.mark.asyncio
async def test_unresolved_consumer_lag_routes_to_consumer_groups_without_placeholder():
    plan = await run_planner("consumer lag 확인해줘", "proj_001")

    names = _tool_names(plan)
    assert "get_consumer_groups" in names
    assert "get_consumer_lag" not in names
    assert all(step.params.get("consumer_group") != "default-group" for step in plan.retrieval_plan)
    assert all(step.params.get("connector_name") != "default-connector" for step in plan.retrieval_plan)


@pytest.mark.asyncio
async def test_single_project_connector_resolved_from_topology():
    class FakeRegistry:
        def __init__(self) -> None:
            self.calls = []

        async def call_tool_with_data(self, tool_name, params, context):
            self.calls.append((tool_name, params))
            result = ToolResult(
                tool_name=tool_name,
                status=ToolStatus.SUCCESS,
                risk=RiskLevel.READ_ONLY,
                summary="ok",
                evidence_ids=[],
            )
            if tool_name == "list_project_pipelines":
                return result, {"pipelines": [{"id": "pipe-1", "name": "orders"}]}
            if tool_name == "get_pipeline_topology":
                return result, {"connectors": [{"name": "orders-sink", "kind": "sink"}]}
            raise AssertionError(tool_name)

    context = ToolContext(
        run_id="run_001",
        step_id="planner",
        agent_name="planner",
        project_id="proj_001",
        request_id="req_001",
    )
    registry = FakeRegistry()

    plan = await run_planner(
        "consumer lag 확인해줘",
        "proj_001",
        registry=registry,
        tool_context=context,
    )

    lag_step = next(step for step in plan.retrieval_plan if step.tool_name == "get_consumer_lag")
    assert lag_step.params == {"consumer_group": "connect-orders-sink"}
    assert registry.calls == [
        ("list_project_pipelines", {}),
        ("get_pipeline_topology", {"pipeline_id": "pipe-1"}),
    ]
