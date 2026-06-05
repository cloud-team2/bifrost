from __future__ import annotations

from datetime import UTC, datetime

import httpx
import pytest

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


def test_registry_exposes_read_tool_allowlist_and_risk():
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(200)))

    tool_names = {definition.name for definition in registry.list_tools()}
    consumer_lag = registry.get_definition("get_consumer_lag")
    legacy_lag = registry.get_definition("get_kafka_lag")

    assert {
        "list_project_pipelines",
        "get_pipeline_topology",
        "get_connector_status",
        "get_consumer_lag",
        "search_logs",
        "get_incident_summary",
    }.issubset(tool_names)
    assert consumer_lag is not None
    assert consumer_lag.risk == "read_only"
    assert legacy_lag is not None
    assert legacy_lag.alias_for == "get_consumer_lag"


@pytest.mark.asyncio
async def test_unknown_tool_is_blocked():
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(200)))

    result = await registry.call_tool("run_sql", {}, _context())

    assert result.status == ToolStatus.BLOCKED
    assert result.risk == "forbidden"
    assert result.error is not None
    assert result.error.code == SpringErrorCode.POLICY_DENIED


@pytest.mark.asyncio
async def test_schema_validation_failure_does_not_call_spring():
    called = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200)

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool("get_consumer_lag", {}, _context())

    assert called is False
    assert result.status == ToolStatus.FAILED
    assert result.error is not None
    assert result.error.code == SpringErrorCode.VALIDATION_FAILED


@pytest.mark.asyncio
async def test_read_tool_success_injects_agent_headers_without_idempotency_key():
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "req_001",
                "operation": "get_consumer_lag",
                "result": {
                    "consumer_group": "orders-consumer",
                    "total_lag": 42,
                    "partitions": [
                        {
                            "topic": "orders",
                            "partition": 0,
                            "current_offset": 100,
                            "log_end_offset": 142,
                            "lag": 42,
                        }
                    ],
                    "observed_at": datetime.now(UTC).isoformat(),
                },
                "evidence": [
                    {
                        "evidence_id": "ev_metric_001",
                        "store_ref": "evidence://run_001/ev_metric_001",
                        "summary": "consumer lag snapshot",
                        "redaction_status": "redacted",
                        "type": "metric",
                    }
                ],
                "audit_event_id": "audit_001",
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool(
        "get_consumer_lag",
        {"consumer_group": "orders-consumer"},
        _context(),
    )

    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/ops/projects/proj_001/kafka/consumer-groups/orders-consumer/lag"
    assert captured_request.headers["X-Agent-Run-Id"] == "run_001"
    assert captured_request.headers["X-Agent-Step-Id"] == "step_001"
    assert captured_request.headers["X-Agent-Name"] == "Retrieval"
    assert captured_request.headers["X-Request-Id"] == "req_001"
    assert captured_request.headers["X-Actor-Type"] == "agent"
    assert captured_request.headers["X-Actor-Id"] == "bifrost-agent"
    assert "X-Idempotency-Key" not in captured_request.headers
    assert result.status == ToolStatus.SUCCESS
    assert result.risk == "read_only"
    assert result.evidence_ids == ["ev_metric_001"]
    assert result.audit_event_id == "audit_001"


@pytest.mark.asyncio
async def test_spring_failure_envelope_becomes_failed_tool_result():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            503,
            json={
                "ok": False,
                "request_id": "req_001",
                "operation": "get_consumer_lag",
                "error": {
                    "code": "UPSTREAM_UNAVAILABLE",
                    "message": "Kafka admin is unavailable",
                    "retryable": True,
                },
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool(
        "get_consumer_lag",
        {"consumer_group": "orders-consumer"},
        _context(),
    )

    assert result.status == ToolStatus.FAILED
    assert result.error is not None
    assert result.error.code == SpringErrorCode.UPSTREAM_UNAVAILABLE
    assert result.error.retryable is True


@pytest.mark.asyncio
async def test_timeout_exception_becomes_timeout_tool_result():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("Spring timed out", request=request)

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool(
        "get_consumer_lag",
        {"consumer_group": "orders-consumer"},
        _context(),
    )

    assert result.status == ToolStatus.TIMEOUT
    assert result.error is not None
    assert result.error.code == SpringErrorCode.TIMEOUT
    assert result.error.retryable is True


@pytest.mark.asyncio
async def test_network_exception_becomes_failed_tool_result():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool(
        "get_consumer_lag",
        {"consumer_group": "orders-consumer"},
        _context(),
    )

    assert result.status == ToolStatus.FAILED
    assert result.error is not None
    assert result.error.code == SpringErrorCode.TRANSIENT_ERROR
    assert result.error.retryable is True
