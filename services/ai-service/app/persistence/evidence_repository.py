"""Repository for redacted raw evidence payloads."""
from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Union

import asyncpg
from pydantic import BaseModel, ConfigDict, field_validator

from app.core.db import get_pool


class RawEvidenceRecord(BaseModel):
    model_config = ConfigDict(extra="ignore")

    store_ref: str
    run_id: str
    evidence_id: str
    tool_name: str | None = None
    step_id: str | None = None
    status: str
    payload: dict[str, Any] | list[Any] | str | int | float | bool | None
    redaction_status: str = "redacted"
    created_at: datetime | None = None

    @field_validator("payload", mode="before")
    @classmethod
    def _jsonb_to_payload(cls, value: object) -> object:
        if isinstance(value, str):
            try:
                return json.loads(value)
            except json.JSONDecodeError:
                return value
        return value


class InMemoryEvidenceRepository:
    def __init__(self) -> None:
        self._store: dict[str, RawEvidenceRecord] = {}
        self._run_index: dict[str, list[str]] = defaultdict(list)

    async def put(
        self,
        *,
        run_id: str,
        evidence_id: str,
        payload: dict[str, Any] | list[Any] | str | int | float | bool | None,
        status: str,
        tool_name: str | None = None,
        step_id: str | None = None,
        failed: bool = False,
    ) -> str:
        store_ref = _store_ref(run_id, evidence_id, failed=failed)
        record = RawEvidenceRecord(
            store_ref=store_ref,
            run_id=run_id,
            evidence_id=evidence_id,
            tool_name=tool_name,
            step_id=step_id,
            status=status,
            payload=payload,
            created_at=datetime.now(timezone.utc),
        )
        self._store[store_ref] = record
        self._run_index[run_id].append(store_ref)
        return store_ref

    async def get(self, store_ref: str) -> RawEvidenceRecord | None:
        return self._store.get(store_ref)

    def clear(self) -> None:
        self._store.clear()
        self._run_index.clear()


class PostgresEvidenceRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def put(
        self,
        *,
        run_id: str,
        evidence_id: str,
        payload: dict[str, Any] | list[Any] | str | int | float | bool | None,
        status: str,
        tool_name: str | None = None,
        step_id: str | None = None,
        failed: bool = False,
    ) -> str:
        store_ref = _store_ref(run_id, evidence_id, failed=failed)
        async with self._get_pool().acquire() as conn:
            await conn.execute(
                """
                INSERT INTO raw_evidence
                    (store_ref, run_id, evidence_id, tool_name, step_id, status, payload, redaction_status)
                VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, 'redacted')
                ON CONFLICT (store_ref) DO UPDATE SET
                    tool_name = EXCLUDED.tool_name,
                    step_id = EXCLUDED.step_id,
                    status = EXCLUDED.status,
                    payload = EXCLUDED.payload,
                    redaction_status = EXCLUDED.redaction_status
                """,
                store_ref,
                run_id,
                evidence_id,
                tool_name,
                step_id,
                status,
                json.dumps(payload),
            )
        return store_ref

    async def get(self, store_ref: str) -> RawEvidenceRecord | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM raw_evidence WHERE store_ref = $1",
                store_ref,
            )
        return RawEvidenceRecord(**dict(row)) if row else None


AnyEvidenceRepo = Union[InMemoryEvidenceRepository, PostgresEvidenceRepository]

_memory_repo = InMemoryEvidenceRepository()
_postgres_repo = PostgresEvidenceRepository()


def get_evidence_repo() -> AnyEvidenceRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo


def _store_ref(run_id: str, evidence_id: str, *, failed: bool = False) -> str:
    suffix = "/failed" if failed else ""
    return f"evidence://{run_id}/{evidence_id}{suffix}"
