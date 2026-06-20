from __future__ import annotations

from fastapi.testclient import TestClient

from app.core.drift import build_drift_report
from app.main import app
from app.persistence.online_feedback_repository import OnlineFeedbackEvent, get_online_feedback_repo
from app.persistence.run_repository import InMemoryRunRecord, get_run_repo

client = TestClient(app)


def test_online_feedback_accept_modify_override_schema_is_stored() -> None:
    run_id = "run_online_feedback_create"
    run_repo = get_run_repo()
    run_repo._store.pop(run_id, None)
    run_repo._store[run_id] = InMemoryRunRecord(run_id=run_id, project_id="proj_001")
    repo = get_online_feedback_repo()
    repo._store.clear()

    resp = client.post(
        f"/api/v1/agent/runs/{run_id}/online-feedback",
        json={
            "action": "override",
            "original_root_cause_id": "CONNECTOR_TASK_FAILED",
            "final_root_cause_id": "SOURCE_AUTH_EXPIRED",
            "original_confidence": 0.82,
            "final_confidence": 0.91,
            "modified_fields": ["root_cause_id", "runbook"],
            "override_reason": "operator found expired source credential",
            "operator_id": "ops-1",
            "metadata": {"ticket": "INC-001"},
        },
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["event_id"].startswith("ofb_")
    assert len(repo._store) == 1
    event = repo._store[0]
    assert event.action == "override"
    assert event.final_root_cause_id == "SOURCE_AUTH_EXPIRED"
    assert event.modified_fields == ["root_cause_id", "runbook"]


def test_online_feedback_run_not_found() -> None:
    resp = client.post(
        "/api/v1/agent/runs/run_missing_feedback/online-feedback",
        json={"action": "accept", "original_root_cause_id": "CONNECTOR_TASK_FAILED"},
    )

    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "RUN_NOT_FOUND"


def test_drift_report_flags_unknown_overprediction_and_override_spike() -> None:
    events = [
        OnlineFeedbackEvent(
            event_id=f"ofb_{idx}",
            run_id=f"run_{idx}",
            action="override" if idx < 3 else "accept",
            original_root_cause_id="CONNECTOR_TASK_FAILED",
            final_root_cause_id="UNKNOWN_WITH_EVIDENCE_GAP" if idx < 2 else "SOURCE_AUTH_EXPIRED",
            original_confidence=0.95,
            final_confidence=0.99,
        )
        for idx in range(6)
    ]

    report = build_drift_report(events)

    assert report["event_count"] == 6
    assert report["recalibration_triggered"] is True
    assert "operator_override_increase" in report["drift_signals"]
    assert "root_cause_overprediction" in report["drift_signals"]
    assert "unknown_ratio_spike" in report["drift_signals"]


def test_drift_report_endpoint_uses_online_feedback_events() -> None:
    repo = get_online_feedback_repo()
    repo._store.clear()
    for idx in range(5):
        repo._store.append(
            OnlineFeedbackEvent(
                event_id=f"ofb_endpoint_{idx}",
                run_id=f"run_endpoint_{idx}",
                action="override",
                original_root_cause_id="CONNECTOR_TASK_FAILED",
                final_root_cause_id="SOURCE_AUTH_EXPIRED",
                original_confidence=0.99,
                final_confidence=0.99,
            )
        )

    resp = client.get("/api/v1/agent/drift-report")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["event_count"] == 5
    assert body["data"]["recalibration_triggered"] is True
