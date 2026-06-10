"""Repository for agent_run table (Agent Run Store §9.2)."""
from __future__ import annotations

from datetime import datetime
from typing import Union

import asyncpg
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.core.config import settings
from app.core.db import get_pool


class RunRecord(BaseModel):
    model_config = ConfigDict(extra="ignore")

    run_id: str
    project_id: str | None = None
    requested_by: str | None = None
    mode: str
    remediation_requested: bool = False
    incident_id: str | None = None
    status: str
    current_agent: str | None = None
    catalog_version: str
    user_message: str | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None
    closed_at: datetime | None = None

    @field_validator("project_id", "incident_id", "requested_by", mode="before")
    @classmethod
    def _uuid_to_str(cls, v: object) -> object:
        # agent_run.project_id 등은 uuid 컬럼이라 asyncpg가 UUID 객체로 반환한다.
        return str(v) if isinstance(v, UUID) else v


class PostgresRunRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def create(
        self,
        run_id: str,
        mode: str,
        *,
        project_id: str | None = None,
        requested_by: str | None = None,
        incident_id: str | None = None,
        remediation_requested: bool = False,
        catalog_version: str | None = None,
        user_message: str | None = None,
    ) -> RunRecord:
        cv = catalog_version or settings.catalog_version
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO agent_run
                    (run_id, project_id, requested_by, mode, remediation_requested,
                     incident_id, status, catalog_version, user_message)
                VALUES ($1, $2::uuid, $3, $4, $5, $6, 'running', $7, $8)
                RETURNING *
                """,
                run_id, project_id, requested_by, mode,
                remediation_requested, incident_id, cv, user_message,
            )
        return RunRecord(**dict(row))

    async def get(self, run_id: str) -> RunRecord | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow("SELECT * FROM agent_run WHERE run_id = $1", run_id)
        return RunRecord(**dict(row)) if row else None

    async def list(
        self,
        project_id: str | None = None,
        status: str | None = None,
        limit: int = 20,
    ) -> list[RunRecord]:
        clauses: list[str] = []
        values: list[str | int] = []
        if project_id is not None:
            values.append(project_id)
            clauses.append(f"project_id = ${len(values)}::uuid")
        if status is not None:
            values.append(status)
            clauses.append(f"status = ${len(values)}")

        values.append(max(0, min(limit, 100)))
        limit_placeholder = f"${len(values)}"
        where_sql = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        query = f"""
            SELECT * FROM agent_run
            {where_sql}
            ORDER BY created_at DESC NULLS LAST, run_id DESC
            LIMIT {limit_placeholder}
        """
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(query, *values)
        return [RunRecord(**dict(row)) for row in rows]

    async def update_status(
        self,
        run_id: str,
        status: str,
        current_agent: str | None = None,
    ) -> None:
        async with self._get_pool().acquire() as conn:
            await conn.execute(
                """
                UPDATE agent_run
                SET status = $2, current_agent = $3, updated_at = now()
                WHERE run_id = $1
                """,
                run_id, status, current_agent,
            )

    async def close_run(self, run_id: str, status: str) -> None:
        async with self._get_pool().acquire() as conn:
            await conn.execute(
                """
                UPDATE agent_run
                SET status = $2, current_agent = NULL,
                    updated_at = now(), closed_at = now()
                WHERE run_id = $1
                """,
                run_id, status,
            )


class InMemoryRunRecord(BaseModel):
    run_id: str
    project_id: str | None = None
    mode: str = "simple_query"
    status: str = "running"
    current_agent: str | None = None
    user_message: str | None = None
    created_at: datetime = Field(default_factory=datetime.utcnow)


class InMemoryRunRepository:
    def __init__(self) -> None:
        self._store: dict[str, InMemoryRunRecord] = {}

    async def create(
        self,
        run_id: str,
        mode: str,
        *,
        project_id: str | None = None,
        user_message: str | None = None,
    ) -> InMemoryRunRecord:
        rec = InMemoryRunRecord(
            run_id=run_id,
            project_id=project_id,
            mode=mode,
            user_message=user_message,
        )
        self._store[run_id] = rec
        return rec

    async def get(self, run_id: str) -> InMemoryRunRecord | None:
        return self._store.get(run_id)

    async def list(
        self,
        project_id: str | None = None,
        status: str | None = None,
        limit: int = 20,
    ) -> list[InMemoryRunRecord]:
        runs = list(self._store.values())
        if project_id is not None:
            runs = [run for run in runs if run.project_id == project_id]
        if status is not None:
            runs = [run for run in runs if run.status == status]
        runs.sort(key=lambda run: run.created_at, reverse=True)
        return runs[:max(0, min(limit, 100))]

    async def update_status(
        self, run_id: str, status: str, current_agent: str | None = None
    ) -> None:
        rec = self._store.get(run_id)
        if rec:
            rec.status = status
            rec.current_agent = current_agent


AnyRunRepo = Union[InMemoryRunRepository, PostgresRunRepository]

_memory_run_repo = InMemoryRunRepository()


def get_run_repo() -> AnyRunRepo:
    from app.core.db import _pool
    if _pool is None:
        return _memory_run_repo
    return PostgresRunRepository()
