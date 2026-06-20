"""Repository for structured online feedback events (#890)."""
from __future__ import annotations

import json
from datetime import datetime
from typing import Literal, Union
from uuid import uuid4

import asyncpg
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import get_pool

FeedbackAction = Literal["accept", "modify", "override"]


class OnlineFeedbackEvent(BaseModel):
    model_config = ConfigDict(extra="ignore")

    event_id: str
    run_id: str
    action: FeedbackAction
    original_root_cause_id: str | None = None
    final_root_cause_id: str | None = None
    original_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    final_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    modified_fields: list[str] = Field(default_factory=list)
    override_reason: str | None = None
    operator_id: str | None = None
    metadata: dict[str, object] = Field(default_factory=dict)
    created_at: datetime | None = None


class InMemoryOnlineFeedbackRepository:
    def __init__(self) -> None:
        self._store: list[OnlineFeedbackEvent] = []

    async def create(self, event: OnlineFeedbackEvent) -> str:
        event_id = event.event_id or f"ofb_{uuid4().hex[:12]}"
        self._store.append(event.model_copy(update={"event_id": event_id, "created_at": event.created_at or datetime.utcnow()}))
        return event_id

    async def list_recent(self, limit: int = 500) -> list[OnlineFeedbackEvent]:
        bounded = max(1, min(limit, 5_000))
        return sorted(self._store, key=lambda item: item.created_at or datetime.min, reverse=True)[:bounded]


class PostgresOnlineFeedbackRepository:
    """asyncpg-backed repository using the shared app pool."""

    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def create(self, event: OnlineFeedbackEvent) -> str:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO online_feedback_events (
                    run_id, action, original_root_cause_id, final_root_cause_id,
                    original_confidence, final_confidence, modified_fields,
                    override_reason, operator_id, metadata
                )
                VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9, $10::jsonb)
                RETURNING event_id
                """,
                event.run_id,
                event.action,
                event.original_root_cause_id,
                event.final_root_cause_id,
                event.original_confidence,
                event.final_confidence,
                json.dumps(event.modified_fields),
                event.override_reason,
                event.operator_id,
                json.dumps(event.metadata),
            )
        return str(row["event_id"])

    async def list_recent(self, limit: int = 500) -> list[OnlineFeedbackEvent]:
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM online_feedback_events
                ORDER BY created_at DESC
                LIMIT $1
                """,
                max(1, min(limit, 5_000)),
            )
        return [OnlineFeedbackEvent(**dict(row)) for row in rows]


AnyOnlineFeedbackRepo = Union[InMemoryOnlineFeedbackRepository, PostgresOnlineFeedbackRepository]

_memory_repo = InMemoryOnlineFeedbackRepository()
_postgres_repo = PostgresOnlineFeedbackRepository()


def get_online_feedback_repo() -> AnyOnlineFeedbackRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo
