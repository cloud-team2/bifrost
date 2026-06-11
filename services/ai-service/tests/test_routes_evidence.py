from __future__ import annotations

import json
from datetime import UTC, datetime
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.api import routes_evidence
from app.main import app
from app.persistence.evidence_repository import RawEvidenceRecord
from app.persistence.state_repository import StatePatchRecord

client = TestClient(app)


class FakeStateRepo:
    def __init__(self, patches: list[SimpleNamespace]) -> None:
        self._patches = patches

    async def get_patches(self, run_id: str):
        return self._patches


class FakeEvidenceRepo:
    def __init__(self, records: dict[str, RawEvidenceRecord] | None = None) -> None:
        self._records = records or {}

    async def get(self, store_ref: str):
        return self._records.get(store_ref)


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


def test_list_evidence_accepts_asyncpg_jsonb_state_patch_record(monkeypatch):
    observed_at = datetime(2026, 6, 9, 10, 0, tzinfo=UTC)
    repo = FakeStateRepo(
        [
            StatePatchRecord(
                run_id="run_001",
                seq=1,
                namespace="evidence",
                author="Retrieval",
                op="append",
                path="/evidence/items",
                patch=json.dumps({
                    "evidence_id": "ev_001",
                    "type": "metric",
                    "store_ref": "evidence://run_001/ev_001",
                    "summary": "consumer lag snapshot",
                    "redaction_status": "redacted",
                }),
                created_at=observed_at,
            )
        ]
    )
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)

    resp = client.get("/api/v1/agent/runs/run_001/evidence")

    assert resp.status_code == 200
    assert resp.json()["data"]["items"][0] == {
        "evidence_id": "ev_001",
        "type": "metric",
        "store_ref": "evidence://run_001/ev_001",
        "summary": "consumer lag snapshot",
        "redaction_status": "redacted",
        "collected_at": observed_at.isoformat(),
    }


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


def test_get_evidence_hydrates_raw_payload(monkeypatch):
    store_ref = "evidence://run_001/ev_002"
    repo = FakeStateRepo(
        [
            _patch("evidence", {"evidence_id": "ev_002", "summary": "second", "store_ref": store_ref}),
        ]
    )
    raw_repo = FakeEvidenceRepo(
        {
            store_ref: RawEvidenceRecord(
                store_ref=store_ref,
                run_id="run_001",
                evidence_id="ev_002",
                status="success",
                payload={"logs": ["auth/permission error log AccessDenied token expired"]},
            )
        }
    )
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)
    monkeypatch.setattr(routes_evidence, "get_raw_evidence_repo", lambda: raw_repo)

    resp = client.get("/api/v1/agent/runs/run_001/evidence/ev_002")

    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["evidence_id"] == "ev_002"
    assert data["raw"]["payload"] == {"logs": ["auth/permission error log AccessDenied token expired"]}
    assert data["raw"]["redaction_status"] == "redacted"


def test_get_evidence_not_found(monkeypatch):
    repo = FakeStateRepo([_patch("evidence", {"evidence_id": "ev_001", "summary": "first"})])
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)

    resp = client.get("/api/v1/agent/runs/run_001/evidence/missing")

    assert resp.status_code == 404
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "EVIDENCE_NOT_FOUND"
    assert body["error"]["message"] == "evidence not found: missing"


def test_hydrate_returns_raw_payload(monkeypatch):
    store_ref = "evidence://run_001/ev_001"
    repo = FakeStateRepo(
        [_patch("evidence", {"evidence_id": "ev_001", "summary": "first", "store_ref": store_ref})]
    )
    raw_repo = FakeEvidenceRepo(
        {
            store_ref: RawEvidenceRecord(
                store_ref=store_ref,
                run_id="run_001",
                evidence_id="ev_001",
                status="success",
                payload={"metric": "consumer_lag", "total_lag": 42},
            )
        }
    )
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)
    monkeypatch.setattr(routes_evidence, "get_raw_evidence_repo", lambda: raw_repo)

    resp = client.post("/api/v1/agent/runs/run_001/evidence/ev_001/hydrate")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["store_ref"] == store_ref
    assert body["data"]["payload"] == {"metric": "consumer_lag", "total_lag": 42}


def test_hydrate_raw_not_found(monkeypatch):
    repo = FakeStateRepo(
        [_patch("evidence", {"evidence_id": "ev_001", "summary": "first", "store_ref": "evidence://run_001/ev_001"})]
    )
    monkeypatch.setattr(routes_evidence, "get_state_repo", lambda: repo)
    monkeypatch.setattr(routes_evidence, "get_raw_evidence_repo", lambda: FakeEvidenceRepo())

    resp = client.post("/api/v1/agent/runs/run_001/evidence/ev_001/hydrate")

    assert resp.status_code == 404
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "EVIDENCE_RAW_NOT_FOUND"
