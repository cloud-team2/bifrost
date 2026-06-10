"""Event repositories for SSE replay on reconnect.

InMemoryEventRepository — used in tests and local dev without a DB.
PostgresEventRepository  — backed by the run_event table (§9.2).
"""
from __future__ import annotations

import json
from collections import defaultdict
from typing import Union

import asyncpg

from app.core.db import get_pool
from app.schemas.events import StreamingEvent


class InMemoryEventRepository:
    def __init__(self) -> None:
        self._store: dict[str, list[StreamingEvent]] = defaultdict(list)

    def append(self, run_id: str, event: StreamingEvent) -> None:
        self._store[run_id].append(event)

    def get_after(self, run_id: str, last_event_id: str | None) -> list[StreamingEvent]:
        events = self._store[run_id]
        if last_event_id is None:
            return list(events)
        for i, event in enumerate(events):
            if event.event_id == last_event_id:
                return list(events[i + 1:])
        return []


class PostgresEventRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def append(self, run_id: str, event: StreamingEvent) -> None:
        payload = event.payload or None
        async with self._get_pool().acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT 1 FROM agent_run WHERE run_id = $1 FOR UPDATE",
                    run_id,
                )
                await conn.execute(
                    """
                    INSERT INTO run_event (event_id, run_id, seq, type, agent, message, payload)
                    SELECT $1, $2,
                           COALESCE((SELECT MAX(seq) FROM run_event WHERE run_id = $2), 0) + 1,
                           $3, $4, $5, $6::jsonb
                    ON CONFLICT (event_id) DO NOTHING
                    """,
                    event.event_id,
                    run_id,
                    event.type.value,
                    event.agent,
                    event.message,
                    json.dumps(payload) if payload else None,
                )

    async def get_after(
        self,
        run_id: str,
        last_event_id: str | None,
    ) -> list[StreamingEvent]:
        if last_event_id is None:
            async with self._get_pool().acquire() as conn:
                rows = await conn.fetch(
                    "SELECT * FROM run_event WHERE run_id = $1 ORDER BY seq",
                    run_id,
                )
        else:
            async with self._get_pool().acquire() as conn:
                rows = await conn.fetch(
                    """
                    SELECT * FROM run_event
                    WHERE run_id = $1
                      AND seq > (SELECT seq FROM run_event WHERE event_id = $2)
                    ORDER BY seq
                    """,
                    run_id,
                    last_event_id,
                )
        return [_row_to_event(r) for r in rows]


def _row_to_event(row: asyncpg.Record) -> StreamingEvent:
    from app.schemas.events import StreamingEventType
    payload = row["payload"]
    if isinstance(payload, str):
        payload = json.loads(payload)
    return StreamingEvent(
        event_id=row["event_id"],
        run_id=row["run_id"],
        timestamp=row["created_at"],
        type=StreamingEventType(row["type"]),
        agent=row["agent"],
        message=row["message"],
        payload=payload or {},
    )


AnyEventRepo = Union[InMemoryEventRepository, PostgresEventRepository]


async def append_event(repo: AnyEventRepo, run_id: str, event: StreamingEvent) -> None:
    if isinstance(repo, InMemoryEventRepository):
        repo.append(run_id, event)
    else:
        await repo.append(run_id, event)


_postgres_repo = PostgresEventRepository()
_memory_repo = InMemoryEventRepository()


def get_event_repo() -> AnyEventRepo:
    from app.core.db import _pool
    if _pool is None:
        return _memory_repo
    return _postgres_repo
