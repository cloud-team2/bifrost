"""Approval link repository — approval token ↔ run/action 매핑 영속화.

InMemory(개발/테스트) + Postgres(운영) 이중 구현.
run_repository.py 패턴 동일.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime
from typing import Union
from uuid import uuid4

import asyncpg
from pydantic import BaseModel, ConfigDict, Field

from app.core.db import get_pool


class ApprovalLink(BaseModel):
    model_config = ConfigDict(extra="ignore")

    approval_id: str
    run_id: str
    action_id: str
    params_hash: str
    status: str = "pending"   # pending | approved | rejected
    approved_by: str | None = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
    resolved_at: datetime | None = None


def _hash_params(params: dict) -> str:
    return hashlib.sha256(json.dumps(params, sort_keys=True).encode()).hexdigest()[:16]


class InMemoryApprovalLinkRepository:
    def __init__(self) -> None:
        self._store: dict[str, ApprovalLink] = {}

    def create(self, run_id: str, action_id: str, params: dict) -> ApprovalLink:
        link = ApprovalLink(
            approval_id=str(uuid4()),
            run_id=run_id,
            action_id=action_id,
            params_hash=_hash_params(params),
        )
        self._store[link.approval_id] = link
        return link

    def get(self, approval_id: str) -> ApprovalLink | None:
        return self._store.get(approval_id)

    def get_by_action(self, run_id: str, action_id: str) -> ApprovalLink | None:
        for link in self._store.values():
            if link.run_id == run_id and link.action_id == action_id:
                return link
        return None

    def approve(self, approval_id: str, approved_by: str = "operator") -> ApprovalLink | None:
        link = self._store.get(approval_id)
        if link:
            link.status = "approved"
            link.approved_by = approved_by
            link.resolved_at = datetime.utcnow()
        return link

    def reject(self, approval_id: str) -> ApprovalLink | None:
        link = self._store.get(approval_id)
        if link:
            link.status = "rejected"
            link.resolved_at = datetime.utcnow()
        return link

    def list_pending(self, run_id: str) -> list[ApprovalLink]:
        return [l for l in self._store.values() if l.run_id == run_id and l.status == "pending"]


class PostgresApprovalLinkRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def create(self, run_id: str, action_id: str, params: dict) -> ApprovalLink:
        approval_id = str(uuid4())
        params_hash = _hash_params(params)
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO approval_link (approval_id, run_id, action_id, params_hash, status)
                VALUES ($1, $2, $3, $4, 'pending')
                RETURNING *
                """,
                approval_id, run_id, action_id, params_hash,
            )
        return ApprovalLink(**dict(row))

    async def get(self, approval_id: str) -> ApprovalLink | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM approval_link WHERE approval_id = $1", approval_id
            )
        return ApprovalLink(**dict(row)) if row else None

    async def approve(self, approval_id: str, approved_by: str = "operator") -> ApprovalLink | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                UPDATE approval_link
                SET status = 'approved', approved_by = $2, resolved_at = now()
                WHERE approval_id = $1
                RETURNING *
                """,
                approval_id, approved_by,
            )
        return ApprovalLink(**dict(row)) if row else None

    async def reject(self, approval_id: str) -> ApprovalLink | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                UPDATE approval_link
                SET status = 'rejected', resolved_at = now()
                WHERE approval_id = $1
                RETURNING *
                """,
                approval_id,
            )
        return ApprovalLink(**dict(row)) if row else None


AnyApprovalRepo = Union[InMemoryApprovalLinkRepository, PostgresApprovalLinkRepository]

_memory_repo = InMemoryApprovalLinkRepository()


def get_approval_repo() -> InMemoryApprovalLinkRepository:
    return _memory_repo
