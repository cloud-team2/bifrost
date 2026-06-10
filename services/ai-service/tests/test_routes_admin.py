from __future__ import annotations

from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
ADMIN_HEADERS = {"X-Actor-Type": "admin"}


def test_admin_requires_actor_type_header():
    resp = client.get("/api/v1/admin/models")

    assert resp.status_code == 403
    assert resp.json()["detail"] == "FORBIDDEN"


def test_admin_actor_type_admin_ok():
    with patch("app.tools.registry.get_tool_registry") as mock_get_registry:
        mock_get_registry.return_value.health = AsyncMock(return_value=False)
        resp = client.get("/api/v1/admin/dependencies", headers=ADMIN_HEADERS)

    assert resp.status_code == 200
    assert resp.json()["ok"] is True


def test_models_returns_tier_mapping():
    resp = client.get("/api/v1/admin/models", headers=ADMIN_HEADERS)

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["default_model"]
    assert "agent_tier_mapping" in body["data"]
    assert "agent_model_resolved" in body["data"]
    assert body["data"]["provider_status"] in {"ok", "unavailable"}


def test_dependencies_returns_spring_and_db():
    with patch("app.tools.registry.get_tool_registry") as mock_get_registry:
        mock_get_registry.return_value.health = AsyncMock(return_value=True)
        resp = client.get("/api/v1/admin/dependencies", headers=ADMIN_HEADERS)

    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["spring_operations"]["status"] == "ok"
    assert data["spring_operations"]["url"]
    assert data["agent_run_store"]["status"] in {"ok", "unavailable"}
    assert data["agent_run_store"]["url"] == "agentdb"


def test_replay_returns_not_implemented():
    resp = client.post("/api/v1/admin/runs/run_001/replay", headers=ADMIN_HEADERS)

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "NOT_IMPLEMENTED"


def test_catalogs_reload_returns_noop():
    resp = client.post("/api/v1/admin/catalogs/reload", headers=ADMIN_HEADERS)

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["reloaded"] is False
    assert body["data"]["catalog_version"]
