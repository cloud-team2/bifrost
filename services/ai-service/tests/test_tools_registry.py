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
        "get_consumer_groups",
        "list_pipelines",
        "list_connectors",
        "analyze_event_log",
        "search_logs",
        "get_incident_summary",
    }.issubset(tool_names)
    assert consumer_lag is not None
    assert consumer_lag.risk == "read_only"
    assert legacy_lag is not None
    assert legacy_lag.alias_for == "get_consumer_lag"


def test_get_metrics_catalog_enum_exposes_live_backed_logical_metrics():
    # #843: metric이 select box(enum)로 바뀌어 설명의 예시 나열은 제거됨.
    # 13종 논리 메트릭의 discoverability는 이제 params_schema enum이 보장한다.
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(200)))

    definition = registry.get_definition("get_metrics")
    assert definition is not None
    metric_schema = definition.params_model.model_json_schema()["properties"]["metric"]
    enum_values = set(metric_schema["enum"])
    assert enum_values == {
        "pipeline_lag_seconds",
        "consumer_lag_p95",
        "consumer_commit_rate_per_sec",
        "topic_ingress_messages_per_sec",
        "source_freshness_delay_ms",
        "source_watermark_delay_ms",
        "source_event_rate_per_sec",
        "broker_cpu_cores",
        "broker_memory_working_set_bytes",
        "broker_network_receive_bytes_per_sec",
        "broker_network_transmit_bytes_per_sec",
        "broker_fs_read_bytes_per_sec",
        "broker_fs_write_bytes_per_sec",
    }


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
                    "p95_lag": 42.0,
                    "top_lag_partitions": [
                        {
                            "topic": "orders",
                            "partition": 0,
                            "current_offset": 100,
                            "log_end_offset": 142,
                            "lag": 42,
                        }
                    ],
                    "summary": "consumer lag snapshot: lag p95=42",
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
    assert result.result is not None
    assert result.result["p95Lag"] == 42.0
    assert result.result["topLagPartitions"][0]["currentOffset"] == 100
    assert "lag p95=42" in result.summary


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


@pytest.mark.asyncio
async def test_project_scope_consumer_groups_calls_internal_ops_and_keeps_structured_result():
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "req_001",
                "operation": "get_consumer_groups",
                "result": {
                    "consumerGroups": [
                        {"group": "connect-orders-sink", "state": "STABLE", "lag": 7, "owner": "orders"}
                    ],
                    "debugSecret": "do-not-emit",
                },
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool("get_consumer_groups", {}, _context())

    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/ops/projects/proj_001/kafka/consumer-groups"
    assert result.status == ToolStatus.SUCCESS
    assert result.result is not None
    assert result.result["consumerGroups"][0]["group"] == "connect-orders-sink"
    assert "debugSecret" not in result.result


@pytest.mark.asyncio
async def test_analyze_event_log_sends_window_and_level_query_params():
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "req_001",
                "operation": "analyze_event_log",
                "result": {
                    "window": "2h",
                    "level": "warn+",
                    "openIncidents": 0,
                    "criticalIncidents": 0,
                    "critical": [],
                    "warnings": [],
                },
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool("analyze_event_log", {"window": "2h", "level": "warn+"}, _context())

    assert captured_request is not None
    assert captured_request.url.path == "/internal/ops/projects/proj_001/observability/events/summary"
    assert captured_request.url.params["window"] == "2h"
    assert captured_request.url.params["level"] == "warn+"
    assert result.status == ToolStatus.SUCCESS
    assert result.result is not None


@pytest.mark.asyncio
async def test_observability_tools_send_explicit_scope_query_params():
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured[request.url.path] = request
        operation = "list_alerts" if request.url.path.endswith("/alerts") else "analyze_event_log"
        result = (
            {"alerts": [], "summary": "0 alerts matched"}
            if operation == "list_alerts"
            else {
                "window": "2h",
                "level": "warn+",
                "openIncidents": 0,
                "criticalIncidents": 0,
                "critical": [],
                "warnings": [],
            }
        )
        return httpx.Response(
            200,
            json={"ok": True, "request_id": "req_001", "operation": operation, "result": result},
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    await registry.call_tool(
        "get_alerts",
        {"pipeline_id": "pipe-001", "connector_name": "orders-sink"},
        _context(),
    )
    await registry.call_tool(
        "analyze_event_log",
        {"window": "2h", "level": "warn+", "pipeline_id": "pipe-001", "connector_name": "orders-sink"},
        _context(),
    )

    alerts_request = captured["/internal/ops/projects/proj_001/observability/alerts"]
    events_request = captured["/internal/ops/projects/proj_001/observability/events/summary"]
    assert alerts_request.url.params["pipeline_id"] == "pipe-001"
    assert alerts_request.url.params["connector_name"] == "orders-sink"
    assert events_request.url.params["pipeline_id"] == "pipe-001"
    assert events_request.url.params["connector_name"] == "orders-sink"


@pytest.mark.asyncio
async def test_observability_tools_default_to_context_pipeline_scope():
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured[request.url.path] = request
        operation = "list_alerts" if request.url.path.endswith("/alerts") else "analyze_event_log"
        result = (
            {"alerts": [], "summary": "0 alerts matched"}
            if operation == "list_alerts"
            else {
                "window": "2h",
                "level": "warn+",
                "openIncidents": 0,
                "criticalIncidents": 0,
                "critical": [],
                "warnings": [],
            }
        )
        return httpx.Response(
            200,
            json={"ok": True, "request_id": "req_001", "operation": operation, "result": result},
        )

    context = _context().model_copy(update={"pipeline_id": "pipe-from-context"})
    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    events = await registry.call_tool("analyze_event_log", {"window": "2h", "level": "warn+"}, context)
    alerts = await registry.call_tool("get_alerts", {}, context)

    events_request = captured["/internal/ops/projects/proj_001/observability/events/summary"]
    alerts_request = captured["/internal/ops/projects/proj_001/observability/alerts"]
    assert events_request.url.params["pipeline_id"] == "pipe-from-context"
    assert alerts_request.url.params["pipeline_id"] == "pipe-from-context"
    assert events.status == ToolStatus.SUCCESS
    assert alerts.status == ToolStatus.SUCCESS


@pytest.mark.asyncio
async def test_explicit_observability_connector_scope_is_not_overwritten_by_context_pipeline():
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "req_001",
                "operation": "analyze_event_log",
                "result": {
                    "window": "2h",
                    "level": "warn+",
                    "openIncidents": 0,
                    "criticalIncidents": 0,
                    "critical": [],
                    "warnings": [],
                },
            },
        )

    context = _context().model_copy(update={"pipeline_id": "pipe-from-context"})
    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool(
        "analyze_event_log",
        {"window": "2h", "level": "warn+", "connector_name": "orders-sink"},
        context,
    )

    assert captured_request is not None
    assert "pipeline_id" not in captured_request.url.params
    assert captured_request.url.params["connector_name"] == "orders-sink"
    assert result.status == ToolStatus.SUCCESS


def _deployments_response(request: httpx.Request) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "ok": True,
            "request_id": "req_001",
            "operation": "get_recent_changes",
            "result": {"changes": []},
        },
    )


@pytest.mark.asyncio
async def test_get_deployments_forwards_limit_as_query_param():
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return _deployments_response(request)

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool("get_deployments", {"limit": 5}, _context())

    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/ops/projects/proj_001/pipelines/changes"
    assert captured_request.url.params["limit"] == "5"
    assert result.status == ToolStatus.SUCCESS


@pytest.mark.asyncio
async def test_get_deployments_without_limit_omits_query_param():
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return _deployments_response(request)

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))

    result = await registry.call_tool("get_deployments", {}, _context())

    assert captured_request is not None
    assert "limit" not in captured_request.url.params
    assert result.status == ToolStatus.SUCCESS
