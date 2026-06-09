"""Repository for run_feedback table."""
from __future__ import annotations

from datetime import datetime
from typing import Union
from uuid import uuid4

import asyncpg
from pydantic import BaseModel, ConfigDict

from app.core.db import get_pool


class FeedbackRecord(BaseModel):
    model_config = ConfigDict(extra="ignore")

    feedback_id: str
    run_id: str
    rating: int
    category: str
    comment: str | None = None
    submitted_by: str | None = None
    created_at: datetime | None = None


class InMemoryFeedbackRepository:
    def __init__(self) -> None:
        self._store: list[FeedbackRecord] = []

    async def create(
        self,
        *,
        run_id: str,
        rating: int,
        category: str,
        comment: str | None = None,
        submitted_by: str | None = None,
    ) -> str:
        feedback_id = f"fb_{uuid4().hex[:12]}"
        rec = FeedbackRecord(
            feedback_id=feedback_id,
            run_id=run_id,
            rating=rating,
            category=category,
            comment=comment,
            submitted_by=submitted_by,
            created_at=datetime.utcnow(),
        )
        self._store.append(rec)
        return feedback_id

    async def list_by_run(self, run_id: str) -> list[FeedbackRecord]:
        return [record for record in self._store if record.run_id == run_id]


class PostgresFeedbackRepository:
    """asyncpg-backed repository using the shared app pool."""

    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def create(
        self,
        *,
        run_id: str,
        rating: int,
        category: str,
        comment: str | None = None,
        submitted_by: str | None = None,
    ) -> str:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO run_feedback (run_id, rating, category, comment, submitted_by)
                VALUES ($1, $2, $3, $4, $5)
                RETURNING feedback_id
                """,
                run_id,
                rating,
                category,
                comment,
                submitted_by,
            )
        return str(row["feedback_id"])

    async def list_by_run(self, run_id: str) -> list[FeedbackRecord]:
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM run_feedback
                WHERE run_id = $1
                ORDER BY created_at
                """,
                run_id,
            )
        return [FeedbackRecord(**dict(row)) for row in rows]


AnyFeedbackRepo = Union[InMemoryFeedbackRepository, PostgresFeedbackRepository]

_memory_repo = InMemoryFeedbackRepository()
_postgres_repo = PostgresFeedbackRepository()


def get_feedback_repo() -> AnyFeedbackRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo
