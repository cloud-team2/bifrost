"""#474 internal-ops read tool 계약 drift 회귀 테스트.

Spring 의 실제 응답 형태(PipelineResponse / PipelineTopologyResult / IncidentSummaryResult)를
FastAPI result schema 가 ValidationError 없이 수용해 ToolStatus.SUCCESS 를 반환하는지 검증한다.
이전에는 strict schema 와의 불일치로 validate_result 가 실패해 ToolStatus.FAILED 가 됐다.
"""
from __future__ import annotations

import httpx
import pytest

from app.schemas.tools import (
    IncidentSummaryData,
    ListProjectPipelinesData,
    PipelineTopologyData,
    ProjectPipelineSummary,
    ToolContext,
    ToolStatus,
)
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


def _spring_response(operation: str, result):
    return httpx.Response(
        200,
        json={
            "ok": True,
            "request_id": "req_001",
            "operation": operation,
            "result": result,
        },
    )


# ── Spring 실제 응답 fixture ──────────────────────────────────────────────────

# PipelineResponse (id=UUID, createdAt, sourceConnector/sinkConnector 등 flat).
_SPRING_PIPELINE = {
    "id": "11111111-1111-1111-1111-111111111111",
    "name": "orders-cdc",
    "pattern": "direct",
    "status": "active",
    "statusMessage": None,
    "sourceDbId": "22222222-2222-2222-2222-222222222222",
    "sinkDbId": "33333333-3333-3333-3333-333333333333",
    "schema": "public",
    "table": "orders",
    "topic": "orders.public.orders",
    "sourceConnector": "orders-source",
    "sinkConnector": "orders-sink",
    "createdAt": "2026-06-01T00:00:00Z",
}

# PipelineTopologyResult (flat sourceDbId/sinkDbId/topic, connectors=ConnectorResponse[]).
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

# IncidentSummaryResult — 3필드(incidentId/status/note)뿐.
_SPRING_INCIDENT = {
    "incidentId": "inc_001",
    "status": "UNKNOWN",
    "note": "incident read model pending — dependency on monitoring integration",
}


# ── model_validate 단위 검증 ──────────────────────────────────────────────────

def test_project_pipeline_summary_maps_spring_id_to_pipeline_id():
    summary = ProjectPipelineSummary.model_validate(_SPRING_PIPELINE)
    assert summary.pipeline_id == "11111111-1111-1111-1111-111111111111"
    assert summary.source_db_id == "22222222-2222-2222-2222-222222222222"
    assert summary.sink_db_id == "33333333-3333-3333-3333-333333333333"
    assert summary.updated_at is not None  # createdAt fallback


def test_list_project_pipelines_data_accepts_spring_list():
    data = ListProjectPipelinesData.model_validate({"pipelines": [_SPRING_PIPELINE]})
    assert len(data.pipelines) == 1
    assert data.pipelines[0].pipeline_id == "11111111-1111-1111-1111-111111111111"


def test_pipeline_topology_data_builds_nested_from_flat_spring():
    data = PipelineTopologyData.model_validate(_SPRING_TOPOLOGY)
    assert data.pipeline_id == "11111111-1111-1111-1111-111111111111"
    assert data.source is not None
    assert data.source.db_id == "22222222-2222-2222-2222-222222222222"
    assert data.sink is not None
    assert data.sink.db_id == "33333333-3333-3333-3333-333333333333"
    assert data.topics == ["orders.public.orders"]
    assert len(data.connectors) == 1
    assert data.connectors[0].cr_name == "orders-source"
    assert data.connectors[0].state == "RUNNING"


def test_incident_summary_data_accepts_spring_three_fields():
    data = IncidentSummaryData.model_validate(_SPRING_INCIDENT)
    assert data.incident_id == "inc_001"
    assert data.status == "UNKNOWN"
    assert data.severity is None
    assert data.trigger_event is None
    assert data.related_event_count is None
    assert data.grouping_key is None
    assert data.summary == _SPRING_INCIDENT["note"]


# ── registry.call_tool 통합 검증 (ToolStatus.SUCCESS) ─────────────────────────

@pytest.mark.asyncio
async def test_list_project_pipelines_call_tool_success():
    registry = ToolClientRegistry(
        transport=httpx.MockTransport(
            lambda request: _spring_response("list_project_pipelines", [_SPRING_PIPELINE])
        )
    )
    result = await registry.call_tool("list_project_pipelines", {}, _context())
    assert result.status == ToolStatus.SUCCESS


@pytest.mark.asyncio
async def test_get_pipeline_topology_call_tool_success():
    registry = ToolClientRegistry(
        transport=httpx.MockTransport(
            lambda request: _spring_response("get_pipeline_topology", _SPRING_TOPOLOGY)
        )
    )
    result = await registry.call_tool(
        "get_pipeline_topology",
        {"pipeline_id": "11111111-1111-1111-1111-111111111111"},
        _context(),
    )
    assert result.status == ToolStatus.SUCCESS


@pytest.mark.asyncio
async def test_get_incident_summary_call_tool_success():
    registry = ToolClientRegistry(
        transport=httpx.MockTransport(
            lambda request: _spring_response("get_incident_summary", _SPRING_INCIDENT)
        )
    )
    result = await registry.call_tool(
        "get_incident_summary",
        {"incident_id": "inc_001"},
        _context(),
    )
    assert result.status == ToolStatus.SUCCESS
