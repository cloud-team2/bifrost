"""Unit tests for persistence repositories (§9.2).

Uses unittest.mock to replace the asyncpg pool — no real DB needed.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import UUID

import pytest

from app.persistence.change_ticket_repository import (
    InMemoryChangeTicketRepository,
    PostgresChangeTicketRepository,
    STATUS_CHANGE_WINDOW_REQUIRED,
    STATUS_VERIFIED,
)
from app.persistence.event_repository import PostgresEventRepository
from app.persistence.report_repository import InMemoryReportRepository, PostgresReportRepository, ReportSnapshot
from app.persistence.run_repository import PostgresRunRepository, RunRecord
from app.persistence.state_repository import PostgresStateRepository, StatePatchRecord
from app.schemas.events import StreamingEvent, StreamingEventType


def _make_pool(fetchrow_return=None, fetch_return=None, execute_return=None):
    """Build a minimal asyncpg pool mock."""
    conn = AsyncMock()
    conn.fetchrow = AsyncMock(return_value=fetchrow_return)
    conn.fetch = AsyncMock(return_value=fetch_return or [])
    conn.execute = AsyncMock(return_value=execute_return)

    pool = MagicMock()
    pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
    pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)
    return pool, conn


def _now() -> datetime:
    return datetime.now(tz=timezone.utc)


# ---------------------------------------------------------------------------
# test 1: RunRepository create → get
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_run_repository_create_and_get():
    row_data = {
        "run_id": "run-001",
        "project_id": None,
        "requested_by": "tester",
        "mode": "simple_query",
        "remediation_requested": False,
        "incident_id": None,
        "status": "running",
        "current_agent": None,
        "catalog_version": "0.1.0",
        "created_at": _now(),
        "updated_at": _now(),
        "closed_at": None,
    }
    pool, conn = _make_pool(fetchrow_return=row_data)
    repo = PostgresRunRepository(pool=pool)

    record = await repo.create("run-001", "simple_query", requested_by="tester")

    assert isinstance(record, RunRecord)
    assert record.run_id == "run-001"
    assert record.status == "running"
    assert record.mode == "simple_query"


# ---------------------------------------------------------------------------
# test 2: RunRepository update_status
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_run_repository_update_status():
    initial_row = {
        "run_id": "run-002",
        "project_id": None,
        "requested_by": None,
        "mode": "simple_query",
        "remediation_requested": False,
        "incident_id": None,
        "status": "running",
        "current_agent": "planner",
        "catalog_version": "0.1.0",
        "created_at": _now(),
        "updated_at": _now(),
        "closed_at": None,
    }
    updated_row = {**initial_row, "status": "completed", "current_agent": None}

    pool, conn = _make_pool()
    # first call (create) returns initial_row; second call (get) returns updated_row
    conn.fetchrow.side_effect = [initial_row, updated_row]

    repo = PostgresRunRepository(pool=pool)
    await repo.create("run-002", "simple_query")
    await repo.update_status("run-002", "completed", current_agent=None)
    result = await repo.get("run-002")

    assert result is not None
    assert result.status == "completed"
    assert result.current_agent is None


# ---------------------------------------------------------------------------
# test 3: StateRepository append → get_patches (seq order)
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_state_repository_append_and_get():
    patch_rows = [
        {
            "id": 1, "run_id": "run-003", "seq": 1,
            "namespace": "run", "author": "planner", "op": "replace",
            "path": "/status", "patch": {"value": "active"},
            "created_at": _now(),
        },
        {
            "id": 2, "run_id": "run-003", "seq": 2,
            "namespace": "run", "author": "verifier", "op": "replace",
            "path": "/status", "patch": {"value": "verified"},
            "created_at": _now(),
        },
    ]

    pool, conn = _make_pool(fetchrow_return={"seq": 1}, fetch_return=patch_rows)
    repo = PostgresStateRepository(pool=pool)

    seq = await repo.append("run-003", "run", "planner", "replace", "/status", {"value": "active"})
    assert seq == 1

    patches = await repo.get_patches("run-003")
    assert len(patches) == 2
    assert patches[0].seq == 1
    assert patches[1].seq == 2
    assert all(isinstance(p, StatePatchRecord) for p in patches)


@pytest.mark.asyncio
async def test_state_repository_get_patches_accepts_asyncpg_jsonb_and_uuid_values():
    author_id = UUID("8a686502-fc55-4515-b186-396c19293edb")
    patch_rows = [
        {
            "id": 1,
            "run_id": "run-003",
            "seq": 1,
            "namespace": "evidence",
            "author": author_id,
            "op": "append",
            "path": "/evidence/items",
            "patch": json.dumps({"evidence_id": "ev-001", "summary": "collected"}),
            "created_at": _now(),
        },
        {
            "id": 2,
            "run_id": "run-003",
            "seq": 2,
            "namespace": "guards",
            "author": "Supervisor",
            "op": "append",
            "path": "/run/guards",
            "patch": None,
            "created_at": _now(),
        },
    ]
    pool, _ = _make_pool(fetch_return=patch_rows)
    repo = PostgresStateRepository(pool=pool)

    patches = await repo.get_patches("run-003")

    assert patches[0].author == str(author_id)
    assert patches[0].patch == {"evidence_id": "ev-001", "summary": "collected"}
    assert patches[1].patch == {}


# ---------------------------------------------------------------------------
# test 4: EventRepository get_after filters correctly
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_event_repository_get_after():
    ts = _now()

    def make_row(event_id: str, seq: int) -> dict:
        return {
            "event_id": event_id,
            "run_id": "run-004",
            "seq": seq,
            "type": "run_started",
            "agent": None,
            "message": f"msg-{seq}",
            "payload": None,
            "created_at": ts,
        }

    rows = [make_row("evt-002", 2), make_row("evt-003", 3)]
    pool, conn = _make_pool(fetch_return=rows)
    repo = PostgresEventRepository(pool=pool)

    events = await repo.get_after("run-004", last_event_id="evt-001")

    assert len(events) == 2
    assert events[0].event_id == "evt-002"
    assert events[1].event_id == "evt-003"


# ---------------------------------------------------------------------------
# test 5: EventRepository get_after(None) returns everything
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_event_repository_get_after_none():
    ts = _now()

    rows = [
        {
            "event_id": "evt-001",
            "run_id": "run-005",
            "seq": 1,
            "type": "run_started",
            "agent": None,
            "message": "started",
            "payload": None,
            "created_at": ts,
        },
        {
            "event_id": "evt-002",
            "run_id": "run-005",
            "seq": 2,
            "type": "run_completed",
            "agent": None,
            "message": "done",
            "payload": None,
            "created_at": ts,
        },
    ]
    pool, conn = _make_pool(fetch_return=rows)
    repo = PostgresEventRepository(pool=pool)

    events = await repo.get_after("run-005", last_event_id=None)

    assert len(events) == 2
    assert events[0].event_id == "evt-001"
    assert events[1].type == StreamingEventType.RUN_COMPLETED


@pytest.mark.asyncio
async def test_event_repository_get_after_coerces_db_row_values():
    event_id = UUID("44444444-4444-4444-4444-444444444444")
    run_id = UUID("55555555-5555-5555-5555-555555555555")
    rows = [
        {
            "event_id": event_id,
            "run_id": run_id,
            "seq": 1,
            "type": "agent_completed",
            "agent": UUID("66666666-6666-6666-6666-666666666666"),
            "message": None,
            "payload": json.dumps({"ok": True}),
            "created_at": _now(),
        },
    ]
    pool, _ = _make_pool(fetch_return=rows)
    repo = PostgresEventRepository(pool=pool)

    events = await repo.get_after(str(run_id), last_event_id=None)

    assert events[0].event_id == str(event_id)
    assert events[0].run_id == str(run_id)
    assert events[0].agent == "66666666-6666-6666-6666-666666666666"
    assert events[0].message == ""
    assert events[0].payload == {"ok": True}


# ---------------------------------------------------------------------------
# test 6: ReportRepository create → get_latest
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_report_repository_create_and_get_latest():
    row_data = {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "run_id": "run-006",
        "incident_id": None,
        "root_cause_id": "rc_001",
        "confidence": 0.8,
        "verified": True,
        "body": {"answer": "done"},
        "created_at": _now(),
    }
    pool, conn = _make_pool(fetchrow_return=row_data)
    repo = PostgresReportRepository(pool=pool)

    created = await repo.create(
        "run-006",
        {"answer": "done"},
        root_cause_id="rc_001",
        confidence=0.8,
        verified=True,
    )
    latest = await repo.get_latest("run-006")

    assert isinstance(created, ReportSnapshot)
    assert latest is not None
    assert latest.body["answer"] == "done"
    assert latest.verified is True


@pytest.mark.asyncio
async def test_in_memory_report_repository_reloads_final_report():
    repo = InMemoryReportRepository()

    await repo.create("run-007", {"answer": "final"}, verified=True)
    snapshot = await repo.get_latest("run-007")

    assert snapshot is not None
    assert snapshot.body["answer"] == "final"


@pytest.mark.asyncio
async def test_change_ticket_repository_upsert_list_and_status_update():
    repo = InMemoryChangeTicketRepository()

    created = await repo.upsert(
        "run-change-001",
        "act-change-001",
        "CHG-001",
        window="2026-06-09T10:00Z/2026-06-09T11:00Z",
        rollback_plan="rollback connector config",
    )
    updated = await repo.update_status("run-change-001", "act-change-001", STATUS_VERIFIED)
    listed = await repo.list_by_run("run-change-001")

    assert updated is not None
    assert created.id == updated.id
    assert updated.status == STATUS_VERIFIED
    assert [ticket.action_id for ticket in listed] == ["act-change-001"]


@pytest.mark.asyncio
async def test_change_ticket_repository_rejects_unknown_status():
    repo = InMemoryChangeTicketRepository()
    await repo.upsert("run-change-status", "act-change-status", "CHG-STATUS")

    with pytest.raises(ValueError, match="unknown change ticket status"):
        await repo.update_status("run-change-status", "act-change-status", "unknown")


@pytest.mark.asyncio
async def test_postgres_change_ticket_repository_upsert_uses_conflict_key():
    row_data = {
        "id": "550e8400-e29b-41d4-a716-446655440001",
        "run_id": "run-change-002",
        "action_id": "act-change-002",
        "ticket_id": "CHG-002",
        "window": "maintenance-window",
        "rollback_plan": "rollback",
        "status": "submitted",
        "created_at": _now(),
        "updated_at": _now(),
    }
    pool, conn = _make_pool(fetchrow_return=row_data)
    repo = PostgresChangeTicketRepository(pool=pool)

    ticket = await repo.upsert(
        "run-change-002",
        "act-change-002",
        "CHG-002",
        window="maintenance-window",
        rollback_plan="rollback",
    )

    assert ticket.ticket_id == "CHG-002"
    assert ticket.action_id == "act-change-002"
    sql = conn.fetchrow.call_args.args[0]
    assert "ON CONFLICT (run_id, action_id)" in sql


@pytest.mark.asyncio
async def test_postgres_change_ticket_repository_read_and_update_paths():
    base_row = {
        "id": "550e8400-e29b-41d4-a716-446655440002",
        "run_id": "run-change-003",
        "action_id": "act-change-003",
        "ticket_id": "CHG-003",
        "window": "maintenance-window",
        "rollback_plan": "rollback",
        "status": "submitted",
        "created_at": _now(),
        "updated_at": _now(),
    }
    updated_row = {**base_row, "status": STATUS_CHANGE_WINDOW_REQUIRED}
    pool, conn = _make_pool(fetchrow_return=base_row, fetch_return=[base_row])
    repo = PostgresChangeTicketRepository(pool=pool)

    fetched = await repo.get_by_action("run-change-003", "act-change-003")
    listed = await repo.list_by_run("run-change-003")
    conn.fetchrow.return_value = updated_row
    updated = await repo.update_status(
        "run-change-003",
        "act-change-003",
        STATUS_CHANGE_WINDOW_REQUIRED,
    )

    assert fetched is not None
    assert fetched.ticket_id == "CHG-003"
    assert [ticket.action_id for ticket in listed] == ["act-change-003"]
    assert updated is not None
    assert updated.status == STATUS_CHANGE_WINDOW_REQUIRED
    assert conn.fetchrow.call_args.args[1:] == (
        "run-change-003",
        "act-change-003",
        STATUS_CHANGE_WINDOW_REQUIRED,
    )
