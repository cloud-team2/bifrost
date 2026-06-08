"""Repository for state_patch table — append-only State history (§9.2)."""
from __future__ import annotations

from datetime import datetime
from typing import Any

import asyncpg
from pydantic import BaseModel, ConfigDict

from app.core.db import get_pool


class StatePatchRecord(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: int | None = None
    run_id: str
    seq: int
    namespace: str
    author: str
    op: str
    path: str
    patch: dict[str, Any]
    created_at: datetime | None = None


class PostgresStateRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def append(
        self,
        run_id: str,
        namespace: str,
        author: str,
        op: str,
        path: str,
        patch: dict[str, Any],
    ) -> int:
        """Append a patch and return the assigned seq number."""
        import json
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO state_patch (run_id, seq, namespace, author, op, path, patch)
                SELECT $1,
                       COALESCE((SELECT MAX(seq) FROM state_patch WHERE run_id = $1), 0) + 1,
                       $2, $3, $4, $5, $6::jsonb
                RETURNING seq
                """,
                run_id, namespace, author, op, path, json.dumps(patch),
            )
        return row["seq"]

    async def get_patches(
        self,
        run_id: str,
        from_seq: int = 0,
    ) -> list[StatePatchRecord]:
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM state_patch
                WHERE run_id = $1 AND seq > $2
                ORDER BY seq
                """,
                run_id, from_seq,
            )
        return [StatePatchRecord(**dict(r)) for r in rows]
