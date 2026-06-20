"""KEDB record repository (#894)."""
from __future__ import annotations

import json
from datetime import date, datetime
from typing import Union

import asyncpg
from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.catalogs.kedb import list_static_kedb_records
from app.core.db import get_pool


class KedbRecordModel(BaseModel):
    model_config = ConfigDict(extra="ignore")

    root_cause_id: str
    owner: str
    known_symptoms: list[str] = Field(default_factory=list)
    verified_fixes: list[str] = Field(default_factory=list)
    rollback_procedure: str | None = None
    recurrence_count: int = Field(default=0, ge=0)
    last_seen: date | None = None
    incident_links: list[str] = Field(default_factory=list)
    updated_at: datetime | None = None

    @field_validator("last_seen", mode="before")
    @classmethod
    def _coerce_last_seen(cls, value: object) -> object:
        if isinstance(value, str) and value:
            return date.fromisoformat(value)
        return value

    @field_validator("known_symptoms", "verified_fixes", "incident_links", mode="before")
    @classmethod
    def _coerce_json_list(cls, value: object) -> object:
        # asyncpg 는 jsonb 컬럼을 raw JSON 문자열로 돌려준다. RETURNING */SELECT * 로
        # 받은 값이 str 이면 list 로 역직렬화한다(in-memory 경로는 이미 list 라 그대로 통과).
        if isinstance(value, str):
            return json.loads(value) if value.strip() else []
        return value


class InMemoryKedbRepository:
    def __init__(self) -> None:
        self._store: dict[str, KedbRecordModel] = {
            record.root_cause_id: KedbRecordModel(**{
                "root_cause_id": record.root_cause_id,
                "owner": record.owner,
                "known_symptoms": list(record.known_symptoms),
                "verified_fixes": list(record.verified_fixes),
                "rollback_procedure": record.rollback_procedure,
                "recurrence_count": record.recurrence_count,
                "last_seen": record.last_seen,
                "incident_links": list(record.incident_links),
            })
            for record in list_static_kedb_records()
        }

    async def upsert(self, record: KedbRecordModel) -> KedbRecordModel:
        self._store[record.root_cause_id] = record.model_copy(update={"updated_at": datetime.utcnow()})
        return self._store[record.root_cause_id]

    async def get(self, root_cause_id: str) -> KedbRecordModel | None:
        return self._store.get(root_cause_id)

    async def list(self) -> list[KedbRecordModel]:
        return sorted(self._store.values(), key=lambda item: item.root_cause_id)

    async def delete(self, root_cause_id: str) -> bool:
        return self._store.pop(root_cause_id, None) is not None


class PostgresKedbRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def upsert(self, record: KedbRecordModel) -> KedbRecordModel:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO kedb_records (
                    root_cause_id, owner, known_symptoms, verified_fixes,
                    rollback_procedure, recurrence_count, last_seen, incident_links
                )
                VALUES ($1, $2, $3::jsonb, $4::jsonb, $5, $6, $7, $8::jsonb)
                ON CONFLICT (root_cause_id) DO UPDATE SET
                    owner = EXCLUDED.owner,
                    known_symptoms = EXCLUDED.known_symptoms,
                    verified_fixes = EXCLUDED.verified_fixes,
                    rollback_procedure = EXCLUDED.rollback_procedure,
                    recurrence_count = EXCLUDED.recurrence_count,
                    last_seen = EXCLUDED.last_seen,
                    incident_links = EXCLUDED.incident_links,
                    updated_at = now()
                RETURNING *
                """,
                record.root_cause_id,
                record.owner,
                json.dumps(record.known_symptoms),
                json.dumps(record.verified_fixes),
                record.rollback_procedure,
                record.recurrence_count,
                record.last_seen,
                json.dumps(record.incident_links),
            )
        return KedbRecordModel(**dict(row))

    async def get(self, root_cause_id: str) -> KedbRecordModel | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow("SELECT * FROM kedb_records WHERE root_cause_id = $1", root_cause_id)
        return KedbRecordModel(**dict(row)) if row else None

    async def list(self) -> list[KedbRecordModel]:
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch("SELECT * FROM kedb_records ORDER BY root_cause_id")
        return [KedbRecordModel(**dict(row)) for row in rows]

    async def delete(self, root_cause_id: str) -> bool:
        async with self._get_pool().acquire() as conn:
            result = await conn.execute("DELETE FROM kedb_records WHERE root_cause_id = $1", root_cause_id)
        return not result.endswith(" 0")


AnyKedbRepo = Union[InMemoryKedbRepository, PostgresKedbRepository]

_memory_repo = InMemoryKedbRepository()
_postgres_repo = PostgresKedbRepository()


def get_kedb_repo() -> AnyKedbRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo
