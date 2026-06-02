"""Health 엔드포인트 스모크 테스트."""
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_root_health_probe():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_api_health_envelope():
    resp = client.get("/api/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["status"] == "ok"
    assert body["request_id"].startswith("req_")


def test_capabilities_modes():
    resp = client.get("/api/v1/capabilities")
    body = resp.json()
    assert body["ok"] is True
    assert "incident_analysis" in body["data"]["modes"]
