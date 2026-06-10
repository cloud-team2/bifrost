"""GET /api/v1/agent/runs/{run_id}/events/history 회귀 (#393)."""
from __future__ import annotations

from datetime import UTC, datetime

from fastapi.testclient import TestClient

from app.main import app
from app.persistence.event_repository import _memory_repo
from app.schemas.events import StreamingEvent, StreamingEventType

client = TestClient(app)


def _make_event(event_id: str, run_id: str, type_: StreamingEventType = StreamingEventType.TOOL_CALL_STARTED) -> StreamingEvent:
    return StreamingEvent(
        event_id=event_id,
        run_id=run_id,
        timestamp=datetime.now(UTC),
        type=type_,
        message="test event",
    )


def _clear_memory_repo(run_id: str) -> None:
    _memory_repo._store.pop(run_id, None)


def test_events_history_empty_run_returns_empty_list():
    """존재하지 않거나 event 가 없는 run → 빈 events."""
    run_id = "run_history_empty"
    _clear_memory_repo(run_id)

    resp = client.get(f"/api/v1/agent/runs/{run_id}/events/history")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["run_id"] == run_id
    assert body["data"]["events"] == []
    assert body["data"]["next_cursor"] is None


def test_events_history_returns_all_events_when_no_cursor():
    """last_event_id 미지정 → 영속화된 모든 events 반환."""
    run_id = "run_history_all"
    _clear_memory_repo(run_id)
    _memory_repo.append(run_id, _make_event("evt_a", run_id))
    _memory_repo.append(run_id, _make_event("evt_b", run_id))
    _memory_repo.append(run_id, _make_event("evt_c", run_id))

    resp = client.get(f"/api/v1/agent/runs/{run_id}/events/history")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    events = body["data"]["events"]
    assert len(events) == 3
    assert [e["event_id"] for e in events] == ["evt_a", "evt_b", "evt_c"]


def test_events_history_returns_only_after_cursor():
    """?last_event_id=evt_a → evt_a 이후만 (evt_b, evt_c)."""
    run_id = "run_history_cursor"
    _clear_memory_repo(run_id)
    _memory_repo.append(run_id, _make_event("evt_a", run_id))
    _memory_repo.append(run_id, _make_event("evt_b", run_id))
    _memory_repo.append(run_id, _make_event("evt_c", run_id))

    resp = client.get(f"/api/v1/agent/runs/{run_id}/events/history?last_event_id=evt_a")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    events = body["data"]["events"]
    assert len(events) == 2
    assert [e["event_id"] for e in events] == ["evt_b", "evt_c"]


def test_events_history_unknown_cursor_returns_empty():
    """존재하지 않는 last_event_id → 빈 events (catchup 의도 안전)."""
    run_id = "run_history_unknown"
    _clear_memory_repo(run_id)
    _memory_repo.append(run_id, _make_event("evt_a", run_id))

    resp = client.get(f"/api/v1/agent/runs/{run_id}/events/history?last_event_id=evt_nonexistent")

    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["data"]["events"] == []


def test_events_history_response_envelope_shape():
    """응답 envelope: ok / request_id / data{run_id, events[], next_cursor}."""
    run_id = "run_history_shape"
    _clear_memory_repo(run_id)

    resp = client.get(f"/api/v1/agent/runs/{run_id}/events/history")

    assert resp.status_code == 200
    body = resp.json()
    assert set(body.keys()) >= {"ok", "request_id", "data"}
    assert body["request_id"].startswith("req_")
    assert set(body["data"].keys()) == {"run_id", "events", "next_cursor"}
