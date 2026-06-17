"""Agent chat thread repository — 멀티 채팅방(세션, #821).

채팅방(thread)을 1급 레코드로 관리: 생성·소유자별 목록·제목변경·삭제·updated_at 갱신.
turn 저장은 message_repository(agent_message)가 담당하고, 여기선 방 메타만 다룬다.
message_repository / report_repository와 동일한 InMemory/Postgres 이중 구현 패턴.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Union

import asyncpg
from pydantic import BaseModel, ConfigDict, field_validator

from app.core.db import get_pool


def _now() -> datetime:
    return datetime.now(timezone.utc)


class AgentThread(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    project_id: str
    owner: str
    title: str | None = None
    archived: bool = False
    created_at: datetime | None = None
    updated_at: datetime | None = None

    @field_validator("project_id", mode="before")
    @classmethod
    def _uuid_to_str(cls, v: object) -> object:
        return str(v) if v is not None and not isinstance(v, str) else v


class InMemoryThreadRepository:
    def __init__(self) -> None:
        self._store: dict[str, AgentThread] = {}

    async def create(self, *, id: str, project_id: str, owner: str, title: str | None = None) -> AgentThread:
        now = _now()
        thread = AgentThread(
            id=id, project_id=project_id, owner=owner, title=title, created_at=now, updated_at=now
        )
        self._store[id] = thread
        return thread

    async def ensure(self, *, id: str, project_id: str, owner: str) -> AgentThread:
        """없으면 만들고, 있으면 그대로 반환(첫 메시지 lazy 생성용)."""
        existing = self._store.get(id)
        if existing:
            return existing
        return await self.create(id=id, project_id=project_id, owner=owner)

    async def get(self, thread_id: str) -> AgentThread | None:
        return self._store.get(thread_id)

    async def list(self, *, project_id: str, owner: str, limit: int = 100) -> list[AgentThread]:
        rows = [
            t for t in self._store.values()
            if t.project_id == str(project_id) and t.owner == owner and not t.archived
        ]
        rows.sort(key=lambda t: t.updated_at or datetime.min.replace(tzinfo=timezone.utc), reverse=True)
        return rows[: max(limit, 0)]

    async def rename(self, thread_id: str, title: str) -> AgentThread | None:
        thread = self._store.get(thread_id)
        if not thread:
            return None
        updated = thread.model_copy(update={"title": title, "updated_at": _now()})
        self._store[thread_id] = updated
        return updated

    async def set_title_if_empty(self, thread_id: str, title: str) -> None:
        thread = self._store.get(thread_id)
        if thread and not (thread.title or "").strip():
            self._store[thread_id] = thread.model_copy(update={"title": title, "updated_at": _now()})

    async def touch(self, thread_id: str) -> None:
        thread = self._store.get(thread_id)
        if thread:
            self._store[thread_id] = thread.model_copy(update={"updated_at": _now()})

    async def delete(self, thread_id: str) -> None:
        self._store.pop(thread_id, None)


class PostgresThreadRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def create(self, *, id: str, project_id: str, owner: str, title: str | None = None) -> AgentThread:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO agent_thread (id, project_id, owner, title)
                VALUES ($1, $2::uuid, $3, $4)
                ON CONFLICT (id) DO UPDATE SET updated_at = now()
                RETURNING *
                """,
                id, project_id, owner, title,
            )
        return _row_to_thread(row)

    async def ensure(self, *, id: str, project_id: str, owner: str) -> AgentThread:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO agent_thread (id, project_id, owner)
                VALUES ($1, $2::uuid, $3)
                ON CONFLICT (id) DO UPDATE SET updated_at = now()
                RETURNING *
                """,
                id, project_id, owner,
            )
        return _row_to_thread(row)

    async def get(self, thread_id: str) -> AgentThread | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow("SELECT * FROM agent_thread WHERE id = $1", thread_id)
        return _row_to_thread(row) if row else None

    async def list(self, *, project_id: str, owner: str, limit: int = 100) -> list[AgentThread]:
        if limit <= 0:
            return []
        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM agent_thread
                WHERE project_id = $1::uuid AND owner = $2 AND archived = false
                ORDER BY updated_at DESC
                LIMIT $3
                """,
                project_id, owner, min(limit, 500),
            )
        return [_row_to_thread(row) for row in rows]

    async def rename(self, thread_id: str, title: str) -> AgentThread | None:
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                "UPDATE agent_thread SET title = $2, updated_at = now() WHERE id = $1 RETURNING *",
                thread_id, title,
            )
        return _row_to_thread(row) if row else None

    async def set_title_if_empty(self, thread_id: str, title: str) -> None:
        async with self._get_pool().acquire() as conn:
            await conn.execute(
                "UPDATE agent_thread SET title = $2, updated_at = now() "
                "WHERE id = $1 AND (title IS NULL OR btrim(title) = '')",
                thread_id, title,
            )

    async def touch(self, thread_id: str) -> None:
        async with self._get_pool().acquire() as conn:
            await conn.execute("UPDATE agent_thread SET updated_at = now() WHERE id = $1", thread_id)

    async def delete(self, thread_id: str) -> None:
        async with self._get_pool().acquire() as conn:
            # 방을 지우면 그 방의 대화 turn도 함께 정리.
            await conn.execute("DELETE FROM agent_message WHERE thread_id = $1", thread_id)
            await conn.execute("DELETE FROM agent_thread WHERE id = $1", thread_id)


AnyThreadRepo = Union[InMemoryThreadRepository, PostgresThreadRepository]

_memory_repo = InMemoryThreadRepository()
_postgres_repo = PostgresThreadRepository()


def get_thread_repo() -> AnyThreadRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo


def _row_to_thread(row: asyncpg.Record | dict[str, Any]) -> AgentThread:
    data = dict(row)
    return AgentThread(
        id=data["id"],
        project_id=data["project_id"],
        owner=data["owner"],
        title=data.get("title"),
        archived=bool(data.get("archived", False)),
        created_at=data.get("created_at"),
        updated_at=data.get("updated_at"),
    )
