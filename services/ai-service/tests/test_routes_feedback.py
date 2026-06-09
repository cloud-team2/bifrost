from __future__ import annotations

from datetime import UTC, datetime

from fastapi.testclient import TestClient

from app.main import app
from app.persistence.feedback_repository import get_feedback_repo
from app.persistence.run_repository import InMemoryRunRecord, get_run_repo
from app.schemas.events import StreamingEvent, StreamingEventType

client = TestClient(app)


def test_feedback_rating_validation():
    resp = client.post(
        "/api/v1/agent/runs/run_missing/feedback",
        json={"rating": 0, "category": "rca_quality"},
    )

    assert resp.status_code == 422


def test_feedback_creates_record():
    run_id = "run_feedback_create"
    run_repo = get_run_repo()
    run_repo._store.pop(run_id, None)
    feedback_repo = get_feedback_repo()
    feedback_repo._store.clear()
    run_repo._store[run_id] = InMemoryRunRecord(run_id=run_id, project_id="proj_001")

    resp = client.post(
        f"/api/v1/agent/runs/{run_id}/feedback",
        json={
            "rating": 5,
            "category": "rca_quality",
            "comment": "clear",
            "submitted_by": "tester",
        },
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["feedback_id"].startswith("fb_")
    records = feedback_repo._store
    assert len(records) == 1
    assert records[0].run_id == run_id
    assert records[0].rating == 5


def test_feedback_run_not_found():
    resp = client.post(
        "/api/v1/agent/runs/run_does_not_exist/feedback",
        json={"rating": 3, "category": "report_clarity"},
    )

    assert resp.status_code == 404
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "RUN_NOT_FOUND"


def test_audit_events_filters_correct_types():
    from app.persistence.event_repository import get_event_repo

    run_id = "run_audit_events"
    repo = get_event_repo()
    repo._store[run_id].clear()
    repo.append(
        run_id,
        StreamingEvent(
            event_id="evt_001",
            run_id=run_id,
            timestamp=datetime.now(UTC),
            type=StreamingEventType.EXECUTION_STARTED,
            agent="executor",
            message="started",
        ),
    )
    repo.append(
        run_id,
        StreamingEvent(
            event_id="evt_002",
            run_id=run_id,
            timestamp=datetime.now(UTC),
            type=StreamingEventType.TOOL_CALL_STARTED,
            agent="executor",
            message="ignored",
        ),
    )
    repo.append(
        run_id,
        StreamingEvent(
            event_id="evt_003",
            run_id=run_id,
            timestamp=datetime.now(UTC),
            type=StreamingEventType.RUN_COMPLETED,
            agent=None,
            message="done",
        ),
    )

    resp = client.get(f"/api/v1/agent/runs/{run_id}/audit-events")

    assert resp.status_code == 200
    items = resp.json()["data"]["items"]
    assert [item["event_id"] for item in items] == ["evt_001", "evt_003"]
    assert items[0]["type"] == "execution_started"


def test_audit_event_single_returns_not_implemented():
    resp = client.get("/api/v1/agent/audit-events/audit_001")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "NOT_IMPLEMENTED"
