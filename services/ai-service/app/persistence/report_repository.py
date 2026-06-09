"""Report snapshot repository for final response replay/cache (§9.2)."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any, Union
from uuid import uuid4

import asyncpg
from pydantic import BaseModel, ConfigDict

from app.core.db import get_pool


class ReportSnapshot(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    run_id: str
    incident_id: str | None = None
    root_cause_id: str | None = None
    confidence: float | None = None
    verified: bool = False
    body: dict[str, Any]
    created_at: datetime | None = None


class InMemoryReportRepository:
    def __init__(self) -> None:
        self._store: dict[str, list[ReportSnapshot]] = {}

    async def create(
        self,
        run_id: str,
        body: dict[str, Any],
        *,
        incident_id: str | None = None,
        root_cause_id: str | None = None,
        confidence: float | None = None,
        verified: bool = True,
    ) -> ReportSnapshot:
        snapshot = ReportSnapshot(
            id=str(uuid4()),
            run_id=run_id,
            incident_id=incident_id,
            root_cause_id=root_cause_id,
            confidence=confidence,
            verified=verified,
            body=body,
            created_at=datetime.now(timezone.utc),
        )
        self._store.setdefault(run_id, []).append(snapshot)
        return snapshot

    async def get_latest(self, run_id: str, *, verified_only: bool = True) -> ReportSnapshot | None:
        snapshots = self._store.get(run_id, [])
        if verified_only:
            snapshots = [snapshot for snapshot in snapshots if snapshot.verified]
        return snapshots[-1] if snapshots else None

    async def list_by_incident(
        self,
        incident_id: str,
        *,
        verified_only: bool = True,
    ) -> list[ReportSnapshot]:
        snapshots = [
            snapshot
            for run_snapshots in self._store.values()
            for snapshot in run_snapshots
            if snapshot.incident_id == incident_id
        ]
        if verified_only:
            snapshots = [snapshot for snapshot in snapshots if snapshot.verified]
        return sorted(snapshots, key=lambda snapshot: snapshot.created_at or datetime.min, reverse=True)


class PostgresReportRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def create(
        self,
        run_id: str,
        body: dict[str, Any],
        *,
        incident_id: str | None = None,
        root_cause_id: str | None = None,
        confidence: float | None = None,
        verified: bool = True,
    ) -> ReportSnapshot:
        snapshot_id = str(uuid4())
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO report_snapshot
                    (id, run_id, incident_id, root_cause_id, confidence, verified, body)
                VALUES ($1::uuid, $2, $3, $4, $5, $6, $7::jsonb)
                RETURNING *
                """,
                snapshot_id,
                run_id,
                incident_id,
                root_cause_id,
                confidence,
                verified,
                json.dumps(body, ensure_ascii=False),
            )
        return _row_to_snapshot(row)

    async def get_latest(self, run_id: str, *, verified_only: bool = True) -> ReportSnapshot | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM report_snapshot
                WHERE run_id = $1
                  AND ($2::boolean = false OR verified = true)
                ORDER BY created_at DESC
                LIMIT 1
                """,
                run_id,
                verified_only,
            )
        return _row_to_snapshot(row) if row else None

    async def list_by_incident(
        self,
        incident_id: str,
        *,
        verified_only: bool = True,
    ) -> list[ReportSnapshot]:
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM report_snapshot
                WHERE incident_id = $1
                  AND ($2::boolean = false OR verified = true)
                ORDER BY created_at DESC
                """,
                incident_id,
                verified_only,
            )
        return [_row_to_snapshot(row) for row in rows]


AnyReportRepo = Union[InMemoryReportRepository, PostgresReportRepository]

_memory_repo = InMemoryReportRepository()
_postgres_repo = PostgresReportRepository()


def get_report_repo() -> AnyReportRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo


def _row_to_snapshot(row: asyncpg.Record | dict[str, Any]) -> ReportSnapshot:
    data = dict(row)
    body = data.get("body") or {}
    if isinstance(body, str):
        body = json.loads(body)
    return ReportSnapshot(
        id=str(data["id"]),
        run_id=data["run_id"],
        incident_id=data.get("incident_id"),
        root_cause_id=data.get("root_cause_id"),
        confidence=float(data["confidence"]) if data.get("confidence") is not None else None,
        verified=data.get("verified", False),
        body=body,
        created_at=data.get("created_at"),
    )
