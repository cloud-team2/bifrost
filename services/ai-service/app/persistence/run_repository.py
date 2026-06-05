"""Repository for agent_run table (Agent Run Store §9.2)."""
from __future__ import annotations

from datetime import datetime
from typing import Any

import asyncpg
from pydantic import BaseModel, ConfigDict

from app.core.db import get_pool
from app.core.config import settings


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
    created_at: datetime | None = None
    updated_at: datetime | None = None
    closed_at: datetime | None = None


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
    ) -> RunRecord:
        cv = catalog_version or settings.catalog_version
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO agent_run
                    (run_id, project_id, requested_by, mode, remediation_requested,
                     incident_id, status, catalog_version)
                VALUES ($1, $2::uuid, $3, $4, $5, $6, 'running', $7)
                RETURNING *
                """,
                run_id, project_id, requested_by, mode,
                remediation_requested, incident_id, cv,
            )
        return RunRecord(**dict(row))

    async def get(self, run_id: str) -> RunRecord | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow("SELECT * FROM agent_run WHERE run_id = $1", run_id)
        return RunRecord(**dict(row)) if row else None

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
