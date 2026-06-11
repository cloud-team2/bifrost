from __future__ import annotations

import httpx
import pytest

from app.schemas.state import RiskLevel
from app.schemas.tools import SpringErrorCode, ToolContext, ToolStatus
from app.tools.registry import ToolClientRegistry


def _context() -> ToolContext:
    return ToolContext(
        run_id="run_001",
        step_id="step_001",
        agent_name="Retrieval",
        project_id="proj_001",
        request_id="req_001",
        user_id="user_001",
    )


def test_get_traces_registered():
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(200)))

    definition = registry.get_definition("get_traces")

    assert definition is not None
    assert definition.operation == "query_traces"
    assert definition.method == "GET"
    assert definition.path_template == "/internal/ops/projects/{project_id}/connectors/{connector_name}/traces"
    assert definition.path_params == ("connector_name",)
    assert definition.risk == RiskLevel.READ_ONLY


def test_get_alerts_registered():
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(200)))

    definition = registry.get_definition("get_alerts")

    assert definition is not None
    assert definition.operation == "list_alerts"
    assert definition.method == "GET"
    assert definition.path_template == "/internal/ops/projects/{project_id}/observability/alerts"
    assert definition.risk == RiskLevel.READ_ONLY


def test_get_connector_task_trace_registered():
    # #368/#373: 에러 trace 조회를 분산 trace(get_traces)와 분리한 별도 tool.
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(200)))

    definition = registry.get_definition("get_connector_task_trace")

    assert definition is not None
    assert definition.operation == "get_connector_task_trace"
    assert definition.method == "GET"
    assert definition.path_template == "/internal/ops/projects/{project_id}/connectors/{connector_name}/task-trace"
    assert definition.path_params == ("connector_name",)
    assert definition.risk == RiskLevel.READ_ONLY


@pytest.mark.asyncio
async def test_get_traces_returns_tempo_trace_summary():
    # #373: query_traces 가 Tempo 분산 trace summary(traceId/pipelineId/status/durationMs/spans)를 반환.
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "req_001",
                "operation": "query_traces",
                "result": {
                    "traceId": "abc123",
                    "pipelineId": "p1",
                    "status": "error",
                    "durationMs": 9,
                    "spans": [
                        {
                            "name": "sink-put",
                            "service": "platform-connect",
                            "durationMs": 4,
                            "status": "error",
                            "error": "type mismatch",
                        }
                    ],
                },
                "evidence": [
                    {
                        "evidence_id": "ev_trace_001",
                        "store_ref": "evidence://run_001/ev_trace_001",
                        "summary": "distributed trace summary",
                        "redaction_status": "redacted",
                        "type": "trace",
                    }
                ],
                "audit_event_id": "audit_001",
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool(
        "get_traces",
        {"connector_name": "default-connector"},
        _context(),
    )

    assert captured_request is not None
    assert captured_request.url.path == "/internal/ops/projects/proj_001/connectors/default-connector/traces"
    assert result.status == ToolStatus.SUCCESS
    assert result.risk == RiskLevel.READ_ONLY
    # 구조적 요약(민감정보 미노출): span 수 + 상태
    assert "spans: 1" in result.summary
    assert "status=error" in result.summary
    assert result.evidence_ids == ["ev_trace_001"]


@pytest.mark.asyncio
async def test_get_connector_task_trace_call_to_spring_mocked():
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "req_001",
                "operation": "get_connector_task_trace",
                "result": {
                    "connector": "default-connector",
                    "summary": "1 connector task trace collected",
                    "traces": [
                        {
                            "taskId": 0,
                            "state": "FAILED",
                            "trace": "java.sql.SQLTransientConnectionException: timeout",
                        }
                    ],
                },
                "evidence": [
                    {
                        "evidence_id": "ev_tasktrace_001",
                        "store_ref": "evidence://run_001/ev_tasktrace_001",
                        "summary": "connector task exception trace",
                        "redaction_status": "redacted",
                        "type": "trace",
                    }
                ],
                "audit_event_id": "audit_001",
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool(
        "get_connector_task_trace",
        {"connector_name": "default-connector"},
        _context(),
    )

    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/ops/projects/proj_001/connectors/default-connector/task-trace"
    assert result.status == ToolStatus.SUCCESS
    assert result.summary == "1 connector task trace collected"
    assert result.evidence_ids == ["ev_tasktrace_001"]


@pytest.mark.asyncio
async def test_get_traces_params_validation():
    called = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200)

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool("get_traces", {}, _context())

    assert called is False
    assert result.status == ToolStatus.FAILED
    assert result.error is not None
    assert result.error.code == SpringErrorCode.VALIDATION_FAILED


def test_existing_tools_intact():
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(200)))

    tool_names = {definition.name for definition in registry.list_tools()}

    assert {
        "list_project_pipelines",
        "get_pipeline_topology",
        "get_connector_status",
        "get_consumer_lag",
        "get_kafka_lag",
        "search_logs",
        "get_metrics",
        "get_incident_summary",
    }.issubset(tool_names)
