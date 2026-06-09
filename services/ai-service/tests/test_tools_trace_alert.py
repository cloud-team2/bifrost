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


@pytest.mark.asyncio
async def test_get_traces_call_to_spring_mocked():
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
                        "evidence_id": "ev_trace_001",
                        "store_ref": "evidence://run_001/ev_trace_001",
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
        "get_traces",
        {"connector_name": "default-connector"},
        _context(),
    )

    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/ops/projects/proj_001/connectors/default-connector/traces"
    assert "connector_name" not in captured_request.url.params
    assert result.status == ToolStatus.SUCCESS
    assert result.risk == RiskLevel.READ_ONLY
    assert result.summary == "1 connector task trace collected"
    assert result.evidence_ids == ["ev_trace_001"]


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
