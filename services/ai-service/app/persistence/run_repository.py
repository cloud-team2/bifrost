"""Repository for agent_run table (Agent Run Store §9.2)."""
from __future__ import annotations

import json
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

    async def list_run_ids_by_incident(
        self,
        incident_id: str,
        *,
        exclude_run_id: str | None = None,
        limit: int = 20,
    ) -> list[str]:
        """같은 incident_id의 run_id를 최신순으로 반환한다(#479 cross-turn 재사용).

        exclude_run_id는 현재 run을 제외한다. created_at DESC 정렬이므로 호출측은
        가장 최근 직전 turn부터 조치 후보·policy State를 찾을 수 있다.
        """
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT run_id FROM agent_run
                WHERE incident_id = $1
                  AND ($2::text IS NULL OR run_id <> $2)
                ORDER BY created_at DESC NULLS LAST, run_id DESC
                LIMIT $3
                """,
                incident_id, exclude_run_id, max(1, min(limit, 100)),
            )
        return [str(row["run_id"]) for row in rows]

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

    async def save_reproducibility(self, run_id: str, manifest: dict) -> None:
        """#885 run 의 재현성 manifest 를 upsert 저장한다(run 시작 시 1회)."""
        async with self._get_pool().acquire() as conn:
            await conn.execute(
                """
                INSERT INTO run_reproducibility
                    (run_id, model_id, model_tier_map, prompt_version, prompt_hash,
                     catalog_version, evidence_matrix_version, runbook_version,
                     corpus_manifest_hash, eval_dataset_version, code_commit_sha, temperature)
                VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7, $8, $9, $10, $11, $12)
                ON CONFLICT (run_id) DO NOTHING
                """,
                run_id,
                manifest["model_id"],
                json.dumps(manifest.get("model_tier_map") or {}),
                manifest["prompt_version"],
                manifest["prompt_hash"],
                manifest["catalog_version"],
                manifest["evidence_matrix_version"],
                manifest["runbook_version"],
                manifest["corpus_manifest_hash"],
                manifest.get("eval_dataset_version", "none"),
                manifest.get("code_commit_sha", "unknown"),
                float(manifest.get("temperature", 0.0)),
            )

    async def get_reproducibility(self, run_id: str) -> dict | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM run_reproducibility WHERE run_id = $1", run_id
            )
        if row is None:
            return None
        record = dict(row)
        tier_map = record.get("model_tier_map")
        if isinstance(tier_map, str):
            record["model_tier_map"] = json.loads(tier_map)
        return record


    async def save_telemetry(self, run_id: str, telemetry: dict) -> None:
        """#883 run telemetry 를 upsert 저장한다."""
        async with self._get_pool().acquire() as conn:
            await conn.execute(
                """
                INSERT INTO run_telemetry
                    (run_id, total_latency_ms, total_stages, called_agents,
                     called_tools, total_tool_calls, total_llm_calls,
                     total_estimated_tokens, stages, handoff_reasons)
                VALUES ($1, $2, $3, $4::jsonb, $5::jsonb, $6, $7, $8, $9::jsonb, $10::jsonb)
                ON CONFLICT (run_id) DO UPDATE SET
                    total_latency_ms = EXCLUDED.total_latency_ms,
                    total_stages = EXCLUDED.total_stages,
                    called_agents = EXCLUDED.called_agents,
                    called_tools = EXCLUDED.called_tools,
                    total_tool_calls = EXCLUDED.total_tool_calls,
                    total_llm_calls = EXCLUDED.total_llm_calls,
                    total_estimated_tokens = EXCLUDED.total_estimated_tokens,
                    stages = EXCLUDED.stages,
                    handoff_reasons = EXCLUDED.handoff_reasons
                """,
                run_id,
                telemetry.get("total_latency_ms", 0),
                telemetry.get("total_stages", 0),
                json.dumps(telemetry.get("called_agents", [])),
                json.dumps(telemetry.get("called_tools", {})),
                telemetry.get("total_tool_calls", 0),
                telemetry.get("total_llm_calls", 0),
                telemetry.get("total_estimated_tokens", 0),
                json.dumps(telemetry.get("stages", [])),
                json.dumps(telemetry.get("handoff_reasons", [])),
            )

    async def get_telemetry(self, run_id: str) -> dict | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM run_telemetry WHERE run_id = $1", run_id
            )
        if row is None:
            return None
        record = dict(row)
        for key in ("called_agents", "called_tools", "stages", "handoff_reasons"):
            if isinstance(record[key], str):
                record[key] = json.loads(record[key])
        return record


class InMemoryRunRecord(BaseModel):
    run_id: str
    project_id: str | None = None
    incident_id: str | None = None
    remediation_requested: bool = False
    mode: str = "simple_query"
    status: str = "running"
    current_agent: str | None = None
    user_message: str | None = None
    created_at: datetime = Field(default_factory=datetime.utcnow)


class InMemoryRunRepository:
    def __init__(self) -> None:
        self._store: dict[str, InMemoryRunRecord] = {}
        self._reproducibility: dict[str, dict] = {}
        self._telemetry: dict[str, dict] = {}

    async def create(
        self,
        run_id: str,
        mode: str,
        *,
        project_id: str | None = None,
        incident_id: str | None = None,
        remediation_requested: bool = False,
        user_message: str | None = None,
        **_: object,
    ) -> InMemoryRunRecord:
        rec = InMemoryRunRecord(
            run_id=run_id,
            project_id=project_id,
            incident_id=incident_id,
            remediation_requested=remediation_requested,
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

    async def list_run_ids_by_incident(
        self,
        incident_id: str,
        *,
        exclude_run_id: str | None = None,
        limit: int = 20,
    ) -> list[str]:
        runs = [
            run for run in self._store.values()
            if run.incident_id == incident_id and run.run_id != exclude_run_id
        ]
        runs.sort(key=lambda run: run.created_at, reverse=True)
        return [run.run_id for run in runs[:max(1, min(limit, 100))]]

    async def update_status(
        self, run_id: str, status: str, current_agent: str | None = None
    ) -> None:
        rec = self._store.get(run_id)
        if rec:
            rec.status = status
            rec.current_agent = current_agent

    async def save_reproducibility(self, run_id: str, manifest: dict) -> None:
        self._reproducibility.setdefault(run_id, dict(manifest))

    async def get_reproducibility(self, run_id: str) -> dict | None:
        return self._reproducibility.get(run_id)

    async def save_telemetry(self, run_id: str, telemetry: dict) -> None:
        self._telemetry[run_id] = dict(telemetry)

    async def get_telemetry(self, run_id: str) -> dict | None:
        return self._telemetry.get(run_id)


AnyRunRepo = Union[InMemoryRunRepository, PostgresRunRepository]

_memory_run_repo = InMemoryRunRepository()


def get_run_repo() -> AnyRunRepo:
    from app.core.db import _pool
    if _pool is None:
        return _memory_run_repo
    return PostgresRunRepository()
