from __future__ import annotations

from fastapi.testclient import TestClient

from app.agents.report import _diagnosis_block
from app.main import app
from app.persistence.kedb_repository import get_kedb_repo
from app.schemas.outputs import RcaOutput
from app.schemas.state import RootCauseCandidate

client = TestClient(app)


def test_root_cause_catalog_links_static_kedb_summary() -> None:
    resp = client.get("/api/v1/catalogs/root-causes")

    assert resp.status_code == 200
    items = resp.json()["data"]["items"]
    connector = next(item for item in items if item["root_cause_id"] == "CONNECTOR_TASK_FAILED")
    assert connector["kedb"]["owner"] == "bifrost"
    assert connector["kedb"]["recurrence_count"] >= 1
    assert connector["kedb"]["verified_fixes"]


def test_kedb_crud_endpoints() -> None:
    repo = get_kedb_repo()
    repo._store.pop("SINK_AUTH_EXPIRED", None)

    create = client.post(
        "/api/v1/catalogs/kedb",
        json={
            "root_cause_id": "SINK_AUTH_EXPIRED",
            "owner": "customer/shared",
            "known_symptoms": ["sink auth failure"],
            "verified_fixes": ["rotate sink credential"],
            "rollback_procedure": "restore previous secret version",
            "recurrence_count": 3,
            "last_seen": "2026-06-20",
            "incident_links": ["incident://sink-auth-001"],
        },
    )
    assert create.status_code == 200
    assert create.json()["data"]["recurrence_count"] == 3

    get_resp = client.get("/api/v1/catalogs/kedb/SINK_AUTH_EXPIRED")
    assert get_resp.status_code == 200
    assert get_resp.json()["data"]["verified_fixes"] == ["rotate sink credential"]

    update = client.put(
        "/api/v1/catalogs/kedb/SINK_AUTH_EXPIRED",
        json={
            "root_cause_id": "SINK_AUTH_EXPIRED",
            "owner": "customer/shared",
            "known_symptoms": ["sink auth failure", "permission denied"],
            "verified_fixes": ["rotate sink credential", "verify connector auth"],
            "recurrence_count": 4,
        },
    )
    assert update.status_code == 200
    assert update.json()["data"]["recurrence_count"] == 4

    delete = client.delete("/api/v1/catalogs/kedb/SINK_AUTH_EXPIRED")
    assert delete.status_code == 200
    assert delete.json()["data"]["deleted"] is True


def test_report_diagnosis_includes_kedb_owner_and_verified_fixes() -> None:
    block = _diagnosis_block(
        RcaOutput(
            root_cause_candidates=[
                RootCauseCandidate(
                    root_cause_id="CONNECTOR_TASK_FAILED",
                    confidence=0.88,
                    required_evidence_satisfied=True,
                    explanation="connector task failed",
                )
            ]
        ),
        classifier_out=None,
    )

    assert "KEDB:" in block
    assert "owner=bifrost" in block
    assert "restart connector" in block
