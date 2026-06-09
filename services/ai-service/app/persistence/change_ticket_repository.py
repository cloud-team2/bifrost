"""Change ticket repository — run/action ↔ change-management ticket persistence.

InMemory(개발/테스트) + Postgres(운영) 이중 구현.
report_repository.py의 async repository + asyncpg 미가용 시 fallback 패턴을 따른다.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Union
from uuid import uuid4

import asyncpg
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import get_pool

STATUS_SUBMITTED = "submitted"
STATUS_VERIFIED = "verified"
STATUS_CHANGE_TICKET_REQUIRED = "CHANGE_TICKET_REQUIRED"
STATUS_CHANGE_WINDOW_REQUIRED = "CHANGE_WINDOW_REQUIRED"
STATUS_ROLLBACK_PLAN_REQUIRED = "ROLLBACK_PLAN_REQUIRED"
VALID_STATUSES = frozenset({
    STATUS_SUBMITTED,
    STATUS_VERIFIED,
    STATUS_CHANGE_TICKET_REQUIRED,
    STATUS_CHANGE_WINDOW_REQUIRED,
    STATUS_ROLLBACK_PLAN_REQUIRED,
})


class ChangeTicket(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = Field(default_factory=lambda: str(uuid4()))
    run_id: str
    action_id: str
    ticket_id: str
    window: str | None = None
    rollback_plan: str | None = None
    status: str = STATUS_SUBMITTED
    created_at: datetime | None = None
    updated_at: datetime | None = None


class InMemoryChangeTicketRepository:
    def __init__(self) -> None:
        self._store: dict[tuple[str, str], ChangeTicket] = {}

    async def upsert(
        self,
        run_id: str,
        action_id: str,
        ticket_id: str,
        *,
        window: str | None = None,
        rollback_plan: str | None = None,
    ) -> ChangeTicket:
        key = (run_id, action_id)
        now = datetime.now(timezone.utc)
        existing = self._store.get(key)
        ticket = ChangeTicket(
            id=existing.id if existing else str(uuid4()),
            run_id=run_id,
            action_id=action_id,
            ticket_id=ticket_id,
            window=window,
            rollback_plan=rollback_plan,
            status=STATUS_SUBMITTED,
            created_at=existing.created_at if existing else now,
            updated_at=now,
        )
        self._store[key] = ticket
        return ticket

    async def get_by_action(self, run_id: str, action_id: str) -> ChangeTicket | None:
        return self._store.get((run_id, action_id))

    async def list_by_run(self, run_id: str) -> list[ChangeTicket]:
        return sorted(
            [ticket for (stored_run_id, _), ticket in self._store.items() if stored_run_id == run_id],
            key=lambda ticket: (
                ticket.created_at or datetime.min.replace(tzinfo=timezone.utc),
                ticket.action_id,
            ),
        )

    async def update_status(self, run_id: str, action_id: str, status: str) -> ChangeTicket | None:
        _ensure_valid_status(status)
        ticket = self._store.get((run_id, action_id))
        if ticket is None:
            return None
        ticket.status = status
        ticket.updated_at = datetime.now(timezone.utc)
        return ticket


class PostgresChangeTicketRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def upsert(
        self,
        run_id: str,
        action_id: str,
        ticket_id: str,
        *,
        window: str | None = None,
        rollback_plan: str | None = None,
    ) -> ChangeTicket:
        ticket_pk = str(uuid4())
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO change_ticket
                    (id, run_id, action_id, ticket_id, window, rollback_plan, status)
                VALUES ($1::uuid, $2, $3, $4, $5, $6, $7)
                ON CONFLICT (run_id, action_id) DO UPDATE
                SET ticket_id = EXCLUDED.ticket_id,
                    window = EXCLUDED.window,
                    rollback_plan = EXCLUDED.rollback_plan,
                    status = EXCLUDED.status,
                    updated_at = now()
                RETURNING *
                """,
                ticket_pk,
                run_id,
                action_id,
                ticket_id,
                window,
                rollback_plan,
                STATUS_SUBMITTED,
            )
        return _row_to_ticket(row)

    async def get_by_action(self, run_id: str, action_id: str) -> ChangeTicket | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM change_ticket
                WHERE run_id = $1 AND action_id = $2
                """,
                run_id,
                action_id,
            )
        return _row_to_ticket(row) if row else None

    async def list_by_run(self, run_id: str) -> list[ChangeTicket]:
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM change_ticket
                WHERE run_id = $1
                ORDER BY created_at ASC, action_id ASC
                """,
                run_id,
            )
        return [_row_to_ticket(row) for row in rows]

    async def update_status(self, run_id: str, action_id: str, status: str) -> ChangeTicket | None:
        _ensure_valid_status(status)
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                UPDATE change_ticket
                SET status = $3, updated_at = now()
                WHERE run_id = $1 AND action_id = $2
                RETURNING *
                """,
                run_id,
                action_id,
                status,
            )
        return _row_to_ticket(row) if row else None


AnyChangeTicketRepo = Union[InMemoryChangeTicketRepository, PostgresChangeTicketRepository]

_memory_repo = InMemoryChangeTicketRepository()
_postgres_repo = PostgresChangeTicketRepository()


def get_change_ticket_repo() -> AnyChangeTicketRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo


def _ensure_valid_status(status: str) -> None:
    if status not in VALID_STATUSES:
        raise ValueError(f"unknown change ticket status: {status}")


def _row_to_ticket(row: asyncpg.Record | dict[str, Any]) -> ChangeTicket:
    data = dict(row)
    return ChangeTicket(
        id=str(data["id"]),
        run_id=data["run_id"],
        action_id=data["action_id"],
        ticket_id=data["ticket_id"],
        window=data.get("window"),
        rollback_plan=data.get("rollback_plan"),
        status=data.get("status", STATUS_SUBMITTED),
        created_at=data.get("created_at"),
        updated_at=data.get("updated_at"),
    )
