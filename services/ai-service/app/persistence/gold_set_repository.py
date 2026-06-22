"""#887 RCA gold set repository — Postgres + InMemory."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Union

import asyncpg

from app.core.db import get_pool
from app.schemas.gold_set import GoldSetEntry, ReviewStatus


class PostgresGoldSetRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def create(self, entry: GoldSetEntry) -> GoldSetEntry:
        async with self._get_pool().acquire() as conn:
            await conn.execute(
                """
                INSERT INTO rca_gold_set
                    (entry_id, incident_id, accepted_root_cause_id, predicted_root_cause_id,
                     trigger, symptom, contributing_factors, evidence_ids, human_verdict,
                     labels, review_status, reviewed_by, reviewed_at, created_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb, $9, $10::jsonb, $11, $12, $13, $14)
                """,
                entry.entry_id,
                entry.incident_id,
                entry.accepted_root_cause_id,
                entry.predicted_root_cause_id,
                entry.trigger,
                entry.symptom,
                json.dumps(entry.contributing_factors),
                json.dumps(entry.evidence_ids),
                entry.human_verdict,
                json.dumps([l.model_dump() for l in entry.labels]),
                entry.review_status.value,
                entry.reviewed_by,
                entry.reviewed_at,
                entry.created_at,
            )
        return entry

    async def get(self, entry_id: str) -> GoldSetEntry | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM rca_gold_set WHERE entry_id = $1", entry_id
            )
        return self._to_entry(row) if row else None

    async def list(
        self,
        *,
        review_status: ReviewStatus | None = None,
        root_cause_id: str | None = None,
        limit: int = 100,
    ) -> list[GoldSetEntry]:
        clauses: list[str] = []
        values: list[object] = []
        if review_status is not None:
            values.append(review_status.value)
            clauses.append(f"review_status = ${len(values)}")
        if root_cause_id is not None:
            values.append(root_cause_id)
            clauses.append(f"accepted_root_cause_id = ${len(values)}")
        values.append(max(1, min(limit, 500)))
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        query = f"""
            SELECT * FROM rca_gold_set {where}
            ORDER BY created_at DESC LIMIT ${len(values)}
        """
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(query, *values)
        return [self._to_entry(r) for r in rows]

    async def update_review(
        self,
        entry_id: str,
        review_status: ReviewStatus,
        reviewed_by: str,
        human_verdict: str | None = None,
    ) -> GoldSetEntry | None:
        now = datetime.now(timezone.utc)
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                UPDATE rca_gold_set
                SET review_status = $2, reviewed_by = $3, reviewed_at = $4,
                    human_verdict = COALESCE($5, human_verdict)
                WHERE entry_id = $1
                RETURNING *
                """,
                entry_id, review_status.value, reviewed_by, now, human_verdict,
            )
        return self._to_entry(row) if row else None

    async def find_latest_by_incident(self, incident_id: str) -> GoldSetEntry | None:
        """#982 운영자 검수 시 같은 incident 의 기존 항목(backfill 예측 등)을 찾아 갱신용으로 사용."""
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM rca_gold_set WHERE incident_id = $1
                ORDER BY created_at DESC LIMIT 1
                """,
                incident_id,
            )
        return self._to_entry(row) if row else None

    async def set_verdict(
        self,
        entry_id: str,
        *,
        review_status: ReviewStatus,
        reviewed_by: str,
        human_verdict: str | None,
        accepted_root_cause_id: str | None,
    ) -> GoldSetEntry | None:
        """#982 운영자 평결 반영 — 정답(accepted_root_cause_id)과 검수 상태를 함께 갱신."""
        now = datetime.now(timezone.utc)
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                UPDATE rca_gold_set
                SET review_status = $2, reviewed_by = $3, reviewed_at = $4,
                    human_verdict = $5, accepted_root_cause_id = $6
                WHERE entry_id = $1
                RETURNING *
                """,
                entry_id, review_status.value, reviewed_by, now,
                human_verdict, accepted_root_cause_id,
            )
        return self._to_entry(row) if row else None

    async def delete(self, entry_id: str) -> bool:
        async with self._get_pool().acquire() as conn:
            result = await conn.execute(
                "DELETE FROM rca_gold_set WHERE entry_id = $1", entry_id
            )
        return result == "DELETE 1"

    async def count(self, *, review_status: ReviewStatus | None = None) -> int:
        if review_status is not None:
            async with self._get_pool().acquire() as conn:
                row = await conn.fetchrow(
                    "SELECT count(*) FROM rca_gold_set WHERE review_status = $1",
                    review_status.value,
                )
        else:
            async with self._get_pool().acquire() as conn:
                row = await conn.fetchrow("SELECT count(*) FROM rca_gold_set")
        return row["count"] if row else 0

    async def existing_incident_ids(self) -> set[str]:
        """#982 backfill 멱등성 — 이미 gold set 에 적재된 incident_id 집합."""
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                "SELECT DISTINCT incident_id FROM rca_gold_set"
            )
        return {r["incident_id"] for r in rows}

    @staticmethod
    def _to_entry(row: asyncpg.Record) -> GoldSetEntry:
        d = dict(row)
        for key in ("contributing_factors", "evidence_ids", "labels"):
            if isinstance(d[key], str):
                d[key] = json.loads(d[key])
        return GoldSetEntry(**d)


class InMemoryGoldSetRepository:
    def __init__(self) -> None:
        self._store: dict[str, GoldSetEntry] = {}

    async def create(self, entry: GoldSetEntry) -> GoldSetEntry:
        self._store[entry.entry_id] = entry
        return entry

    async def get(self, entry_id: str) -> GoldSetEntry | None:
        return self._store.get(entry_id)

    async def list(
        self,
        *,
        review_status: ReviewStatus | None = None,
        root_cause_id: str | None = None,
        limit: int = 100,
    ) -> list[GoldSetEntry]:
        entries = list(self._store.values())
        if review_status is not None:
            entries = [e for e in entries if e.review_status == review_status]
        if root_cause_id is not None:
            entries = [e for e in entries if e.accepted_root_cause_id == root_cause_id]
        entries.sort(key=lambda e: e.created_at, reverse=True)
        return entries[: max(1, min(limit, 500))]

    async def update_review(
        self,
        entry_id: str,
        review_status: ReviewStatus,
        reviewed_by: str,
        human_verdict: str | None = None,
    ) -> GoldSetEntry | None:
        entry = self._store.get(entry_id)
        if entry is None:
            return None
        updated = entry.model_copy(
            update={
                "review_status": review_status,
                "reviewed_by": reviewed_by,
                "reviewed_at": datetime.now(timezone.utc),
                **({"human_verdict": human_verdict} if human_verdict else {}),
            }
        )
        self._store[entry_id] = updated
        return updated

    async def find_latest_by_incident(self, incident_id: str) -> GoldSetEntry | None:
        matches = [e for e in self._store.values() if e.incident_id == incident_id]
        if not matches:
            return None
        matches.sort(key=lambda e: e.created_at, reverse=True)
        return matches[0]

    async def set_verdict(
        self,
        entry_id: str,
        *,
        review_status: ReviewStatus,
        reviewed_by: str,
        human_verdict: str | None,
        accepted_root_cause_id: str | None,
    ) -> GoldSetEntry | None:
        entry = self._store.get(entry_id)
        if entry is None:
            return None
        updated = entry.model_copy(
            update={
                "review_status": review_status,
                "reviewed_by": reviewed_by,
                "reviewed_at": datetime.now(timezone.utc),
                "human_verdict": human_verdict,
                "accepted_root_cause_id": accepted_root_cause_id,
            }
        )
        self._store[entry_id] = updated
        return updated

    async def delete(self, entry_id: str) -> bool:
        return self._store.pop(entry_id, None) is not None

    async def count(self, *, review_status: ReviewStatus | None = None) -> int:
        if review_status is not None:
            return sum(
                1 for e in self._store.values() if e.review_status == review_status
            )
        return len(self._store)

    async def existing_incident_ids(self) -> set[str]:
        return {e.incident_id for e in self._store.values()}


AnyGoldSetRepo = Union[InMemoryGoldSetRepository, PostgresGoldSetRepository]

_memory_gold_set_repo = InMemoryGoldSetRepository()


def get_gold_set_repo() -> AnyGoldSetRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_gold_set_repo
    return PostgresGoldSetRepository()
