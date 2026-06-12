from __future__ import annotations

import json

import httpx
from fastapi.testclient import TestClient

from app.api import routes_catalogs
from app.catalogs.failure_types import list_failure_types
from app.core.config import settings
from app.main import app
from app.tools.registry import ToolClientRegistry

client = TestClient(app)


def _data(path: str) -> dict:
    resp = client.get(path)
    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    return body["data"]


def test_failure_types_returns_all_catalog():
    data = _data("/api/v1/catalogs/failure-types")

    assert len(data["items"]) == len(list_failure_types())
    assert data["items"][0]["incident_type"] == list_failure_types()[0].incident_type
    assert isinstance(data["items"][0]["signals"], list)


def test_root_causes_includes_unknown():
    data = _data("/api/v1/catalogs/root-causes")

    assert "UNKNOWN_WITH_EVIDENCE_GAP" in {item["root_cause_id"] for item in data["items"]}


def test_incident_root_cause_map_structure():
    data = _data("/api/v1/catalogs/incident-root-cause-map")

    assert isinstance(data["mapping"], dict)
    assert data["mapping"]["UNKNOWN_NEEDS_MORE_EVIDENCE"] == ["UNKNOWN_WITH_EVIDENCE_GAP"]


def test_policies_returns_items():
    data = _data("/api/v1/catalogs/policies")

    assert data["items"]
    assert {
        "action_type": "runtime_tool",
        "risk": "read_only",
        "decision": "allow",
        "reason": "읽기 전용 — 자동 허용",
    } in data["items"]


def test_runbooks_returns_items_with_actions():
    data = _data("/api/v1/catalogs/runbooks")

    connector_runbook = next(item for item in data["items"] if item["root_cause_id"] == "CONNECTOR_TASK_FAILED")
    assert connector_runbook["actions"]
    assert connector_runbook["actions"][0]["action_name"] == "restart_connector_task"


def test_tools_returns_20_tools():
    # #491: structured panel read tools add project-scope consumer/pipeline/connector/event views.
    data = _data("/api/v1/tools")

    assert len(data["tools"]) == 20
    names = {tool["name"] for tool in data["tools"]}
    assert "search_logs" in names
    assert "get_connector_task_trace" in names
    assert {"get_consumer_groups", "list_pipelines", "list_connectors", "analyze_event_log"}.issubset(names)


def test_tools_expose_descriptions():
    # #599: 모든 도구에 사용자 노출용 한글 설명이 내려간다(슬래시 드롭다운 소스).
    data = _data("/api/v1/tools")

    for tool in data["tools"]:
        assert tool.get("description"), f"missing description: {tool['name']}"
    by_name = {tool["name"]: tool["description"] for tool in data["tools"]}
    assert by_name["list_connectors"] == "Kafka Connector 상태 및 Task 정보를 조회합니다."


def test_get_tool_by_name():
    data = _data("/api/v1/tools/search_logs")

    assert data["name"] == "search_logs"
    assert data["operation"] == "search_logs"
    assert data["risk"] == "read_only"
    assert data["description"]
    assert "params_schema" in data
    assert "result_schema" in data


def test_get_tool_not_found():
    resp = client.get("/api/v1/tools/run_sql")

    assert resp.status_code == 404
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "TOOL_NOT_FOUND"


def test_execute_read_tool_calls_registry_without_agent_workflow(monkeypatch):
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["path"] = request.url.path
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "spring_req",
                "operation": "list_connectors",
                "result": {"connectors": []},
                "evidence": [],
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(routes_catalogs, "get_tool_registry", lambda: registry)

    resp = client.post(
        "/api/v1/tools/list_connectors/execute",
        json={"project_id": "proj-001", "params": {}},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["result"] == {"connectors": []}
    assert body["data"]["tool_result"]["tool_name"] == "list_connectors"
    assert captured["path"] == "/internal/ops/projects/proj-001/kafka/connectors/status"


def test_execute_read_tool_allows_read_only_post_tools(monkeypatch):
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["method"] = request.method
        captured["path"] = request.url.path
        captured["body"] = request.content.decode()
        return httpx.Response(
            200,
            json={
                "ok": True,
                "request_id": "spring_req",
                "operation": "search_logs",
                "result": {"logs": [], "total": 0, "note": "empty"},
                "evidence": [],
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(routes_catalogs, "get_tool_registry", lambda: registry)

    resp = client.post(
        "/api/v1/tools/search_logs/execute",
        json={"project_id": "proj-001", "params": {"query": "error", "limit": 10}},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["result"]["logs"] == []
    assert body["data"]["tool_result"]["tool_name"] == "search_logs"
    assert captured["method"] == "POST"
    assert captured["path"] == "/internal/ops/projects/proj-001/observability/logs/search"
    assert json.loads(captured["body"])["query"] == "error"


def test_execute_read_tool_returns_validation_error_for_missing_params(monkeypatch):
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(500)))
    monkeypatch.setattr(routes_catalogs, "get_tool_registry", lambda: registry)

    resp = client.post(
        "/api/v1/tools/search_logs/execute",
        json={"project_id": "proj-001", "params": {}},
    )

    assert resp.status_code == 400
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "VALIDATION_FAILED"


def test_execute_tool_blocks_mutation_tools(monkeypatch):
    registry = ToolClientRegistry(transport=httpx.MockTransport(lambda request: httpx.Response(500)))
    monkeypatch.setattr(routes_catalogs, "get_tool_registry", lambda: registry)

    resp = client.post(
        "/api/v1/tools/pause_connector/execute",
        json={"project_id": "proj-001", "params": {"connector_name": "orders-sink"}},
    )

    assert resp.status_code == 400
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "POLICY_DENIED"


def test_catalog_version_present():
    for path in [
        "/api/v1/catalogs/failure-types",
        "/api/v1/catalogs/root-causes",
        "/api/v1/catalogs/incident-root-cause-map",
        "/api/v1/catalogs/policies",
        "/api/v1/catalogs/runbooks",
    ]:
        assert _data(path)["version"] == settings.catalog_version
