from __future__ import annotations

from fastapi.testclient import TestClient

from app.catalogs.failure_types import list_failure_types
from app.core.config import settings
from app.main import app

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


def test_tools_returns_15_tools():
    data = _data("/api/v1/tools")

    assert len(data["tools"]) == 15
    assert "search_logs" in {tool["name"] for tool in data["tools"]}


def test_get_tool_by_name():
    data = _data("/api/v1/tools/search_logs")

    assert data["name"] == "search_logs"
    assert data["operation"] == "search_logs"
    assert data["risk"] == "read_only"
    assert "params_schema" in data
    assert "result_schema" in data


def test_get_tool_not_found():
    resp = client.get("/api/v1/tools/run_sql")

    assert resp.status_code == 404
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "TOOL_NOT_FOUND"


def test_catalog_version_present():
    for path in [
        "/api/v1/catalogs/failure-types",
        "/api/v1/catalogs/root-causes",
        "/api/v1/catalogs/incident-root-cause-map",
        "/api/v1/catalogs/policies",
        "/api/v1/catalogs/runbooks",
    ]:
        assert _data(path)["version"] == settings.catalog_version
