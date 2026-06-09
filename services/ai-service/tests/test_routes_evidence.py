from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.api import routes_evidence
from app.main import app

client = TestClient(app)


class FakeStateRepo:
    def __init__(self, patches: list[SimpleNamespace]) -> None:
        self._patches = patches

    async def get_patches(self, run_id: str):
        return self._patches


def _patch(namespace: str, payload: dict, created_at: datetime | None = None) -> SimpleNamespace:
    return SimpleNamespace(namespace=namespace, patch=payload, created_at=created_at)


def test_list_evidence_returns_evidence_namespace_only(monkeypatch):
    observed_at = datetime(2026, 6, 9, 10, 0, tzinfo=UTC)
    repo = FakeStateRepo(
        [
            _patch(
                "evidence",
                {
                    "evidence_id": "ev_001",
                    "type": "metric",
                    "store_ref": "evidence://run_001/ev_001",
                    "summary": "consumer lag snapshot",
                    "redaction_status": "redacted",
                },
                observed_at,
            ),
            _patch("analysis", {"evidence_id": "ev_skip", "summary": "wrong namespace"}),
            _patch("evidence", {"summary": "missing id"}),
        ]
    )
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)

    resp = client.get("/api/v1/agent/runs/run_001/evidence")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["items"] == [
        {
            "evidence_id": "ev_001",
            "type": "metric",
            "store_ref": "evidence://run_001/ev_001",
            "summary": "consumer lag snapshot",
            "redaction_status": "redacted",
            "collected_at": observed_at.isoformat(),
        }
    ]


def test_get_evidence_returns_single(monkeypatch):
    repo = FakeStateRepo(
        [
            _patch("evidence", {"evidence_id": "ev_001", "summary": "first"}),
            _patch("evidence", {"evidence_id": "ev_002", "summary": "second"}),
        ]
    )
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)

    resp = client.get("/api/v1/agent/runs/run_001/evidence/ev_002")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"] == {"evidence_id": "ev_002", "summary": "second"}


def test_get_evidence_not_found(monkeypatch):
    repo = FakeStateRepo([_patch("evidence", {"evidence_id": "ev_001", "summary": "first"})])
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)

    resp = client.get("/api/v1/agent/runs/run_001/evidence/missing")

    assert resp.status_code == 404
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "EVIDENCE_NOT_FOUND"
    assert body["error"]["message"] == "evidence not found: missing"


def test_hydrate_returns_not_implemented():
    resp = client.post("/api/v1/agent/runs/run_001/evidence/ev_001/hydrate")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "NOT_IMPLEMENTED"
