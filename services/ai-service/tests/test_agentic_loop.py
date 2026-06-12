"""Agentic ReAct 루프 체이닝 검증 (#633 harness).

flat planner 가 못 하던 도구 체이닝(한 도구 결과를 다음 도구 입력으로)을 루프가 하는지 검증한다.
시나리오: get_pipeline_topology → (결과의 커넥터 이름) → get_connector_status → 최종 답변.
"""
from __future__ import annotations

import json
from types import SimpleNamespace

import httpx
import pytest

from app.agents.agentic import build_tool_schemas, run_tool_loop
from app.schemas.tools import ToolContext, ToolStatus
from app.tools.registry import ToolClientRegistry

# 기존 contract 테스트의 Spring 응답 형태 재사용
_SPRING_TOPOLOGY = {
    "pipelineId": "11111111-1111-1111-1111-111111111111",
    "name": "orders-cdc",
    "pattern": "direct",
    "status": "active",
    "topic": "orders.public.orders",
    "sourceDbId": "22222222-2222-2222-2222-222222222222",
    "sinkDbId": "33333333-3333-3333-3333-333333333333",
    "sourceConnector": "orders-source",
    "sinkConnector": "orders-sink",
    "connectors": [
        {
            "name": "orders-source",
            "kind": "source",
            "connectorClass": "io.debezium.connector.postgresql.PostgresConnector",
            "state": "RUNNING",
            "tasksMax": 1,
            "lastError": None,
            "updatedAt": "2026-06-01T00:00:00Z",
        }
    ],
}
_SPRING_CONNECTOR_STATUS = {
    "connectorName": "orders-source",
    "connectorState": "RUNNING",
    "tasks": [{"id": 0, "state": "RUNNING", "trace": None}],
}


def _spring(operation, result):
    return httpx.Response(
        200, json={"ok": True, "request_id": "req_001", "operation": operation, "result": result}
    )


def _transport(request: httpx.Request) -> httpx.Response:
    path = request.url.path
    if path.endswith("/topology"):
        return _spring("get_pipeline_topology", _SPRING_TOPOLOGY)
    if "/kafka/connectors/" in path and path.endswith("/status"):
        return _spring("get_connector_status", _SPRING_CONNECTOR_STATUS)
    return httpx.Response(404, json={"ok": False})


def _context() -> ToolContext:
    return ToolContext(
        run_id="run_001",
        step_id="step_001",
        agent_name="agentic",
        project_id="proj_001",
        request_id="req_001",
        user_id="user_001",
    )


def _func_call(call_id, name, arguments):
    return SimpleNamespace(id=call_id, function=SimpleNamespace(name=name, arguments=arguments))


def _msg(content=None, tool_calls=None):
    return SimpleNamespace(content=content, tool_calls=tool_calls)


class _ChainingProvider:
    """topology 결과에서 커넥터 이름을 뽑아 connector_status 를 호출하는 스크립트 provider."""

    def __init__(self):
        self.step = 0

    async def generate_with_tools(self, messages, tools, model=None):
        self.step += 1
        if self.step == 1:
            return _msg(
                tool_calls=[
                    _func_call(
                        "c1",
                        "get_pipeline_topology",
                        json.dumps({"pipeline_id": "11111111-1111-1111-1111-111111111111"}),
                    )
                ]
            )
        if self.step == 2:
            # 루프가 직전 tool 결과를 provider 에 돌려줬는지(=chaining 가능) 확인
            tool_msgs = [m for m in messages if m.get("role") == "tool"]
            assert tool_msgs, "루프가 tool 결과를 provider 에 전달하지 않음(chaining 불가)"
            assert "orders-source" in tool_msgs[-1]["content"], "topology 결과가 전달되지 않음"
            return _msg(
                tool_calls=[
                    _func_call("c2", "get_connector_status", json.dumps({"connector_name": "orders-source"}))
                ]
            )
        return _msg(content="파이프라인은 active(동작 중)이며 소스 커넥터 orders-source 는 RUNNING 입니다.")


class _NoLlmProvider:
    async def generate_with_tools(self, messages, tools, model=None):
        return None


def test_build_tool_schemas_only_read_only_and_no_mutation():
    schemas = build_tool_schemas(ToolClientRegistry(transport=httpx.MockTransport(_transport)))
    names = {s["function"]["name"] for s in schemas}
    assert "get_pipeline_topology" in names
    assert "get_connector_status" in names
    # mutation 도구는 제외돼야 한다
    assert "restart_connector" not in names
    assert "pause_connector" not in names


@pytest.mark.asyncio
async def test_loop_chains_topology_into_connector_status():
    registry = ToolClientRegistry(transport=httpx.MockTransport(_transport))
    result = await run_tool_loop(
        user_message="62017606 파이프라인의 상세 현황을 알려줘",
        registry=registry,
        context=_context(),
        provider=_ChainingProvider(),
    )
    assert result.used_llm is True
    # 두 도구가 순서대로 호출됨 — 두 번째는 첫 결과에서 얻은 커넥터 이름으로 chaining
    assert [c.tool_name for c in result.calls] == ["get_pipeline_topology", "get_connector_status"]
    assert result.calls[1].params == {"connector_name": "orders-source"}
    assert all(c.result.status == ToolStatus.SUCCESS for c in result.calls)
    assert "RUNNING" in result.answer or "active" in result.answer


@pytest.mark.asyncio
async def test_loop_falls_back_when_no_llm():
    registry = ToolClientRegistry(transport=httpx.MockTransport(_transport))
    result = await run_tool_loop(
        user_message="현황 알려줘",
        registry=registry,
        context=_context(),
        provider=_NoLlmProvider(),
    )
    assert result.used_llm is False
    assert result.calls == []
