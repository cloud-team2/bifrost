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
    # Spring MutationGate 연동용: preapproved 엔드포인트로 생성된 Spring approval UUID
    spring_approval_id: str | None = None
    # Spring params_hash (64-char SHA256, Spring 실행 시 검증용)
    spring_params_hash: str | None = None
    # 실행할 도구 이름 (Spring preapproved 엔드포인트 toolName 필드용)
    tool_name: str | None = None


def _hash_params(params: dict) -> str:
    return hashlib.sha256(json.dumps(params, sort_keys=True).encode()).hexdigest()[:16]


def spring_params_hash(tool_name: str, project_id: str, tool_params: dict | None) -> str:
    """Spring InternalOpsMutationController.paramsHash와 동일한 형식의 64-char SHA256 해시.

    Spring은 TreeMap(정렬된 키) + Jackson objectMapper(공백 없음) → SHA-256 으로 계산한다.
    separators=(',', ':')로 공백을 제거해야 Jackson 기본 직렬화와 동일한 해시가 나온다.
    """
    params = tool_params or {}
    if "connector" in tool_name:
        target_key, target_value = "connector_name", str(params.get("connector_name", ""))
    elif "consumer_group" in tool_name:
        target_key, target_value = "consumer_group", str(params.get("consumer_group", ""))
    else:
        target_key, target_value = "target", ""
    body = {"project_id": project_id, target_key: target_value, "tool_name": tool_name}
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


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

    def set_spring_approval_id(self, approval_id: str, spring_approval_id: str) -> None:
        link = self._store.get(approval_id)
        if link:
            link.spring_approval_id = spring_approval_id

    def reject(self, approval_id: str) -> ApprovalLink | None:
        link = self._store.get(approval_id)
        if link:
            link.status = "rejected"
            link.resolved_at = datetime.utcnow()
        return link

    def list_pending(self, run_id: str) -> list[ApprovalLink]:
        return [l for l in self._store.values() if l.run_id == run_id and l.status == "pending"]

    def list_all(
        self,
        *,
        status: str | None = None,
        run_id: str | None = None,
    ) -> list[ApprovalLink]:
        """글로벌 approval 조회 (issue #394).

        run/status 필터는 optional. 최신 (created_at desc) 순 정렬.
        """
        items = list(self._store.values())
        if status is not None:
            items = [l for l in items if l.status == status]
        if run_id is not None:
            items = [l for l in items if l.run_id == run_id]
        items.sort(key=lambda l: l.created_at, reverse=True)
        return items


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
