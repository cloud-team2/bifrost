"""Agent conversation message repository — 멀티턴 대화 메모리(thread 모델, #712).

thread_id로 대화 turn(user/assistant)을 저장·조회한다. 인시던트 채팅은 thread_id =
incident_id를 쓰고, run 생성 시 같은 thread의 이전 메시지를 시간순으로 로드해 LLM 컨텍스트에
주입한다. report_repository와 동일한 InMemory/Postgres 이중 구현 패턴을 따른다.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Union
from uuid import uuid4

import asyncpg
from pydantic import BaseModel, ConfigDict, field_validator

from app.core.db import get_pool

ROLE_USER = "user"
ROLE_ASSISTANT = "assistant"


class AgentMessage(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    thread_id: str
    project_id: str | None = None
    role: str
    content: str
    run_id: str | None = None
    created_at: datetime | None = None

    @field_validator("project_id", mode="before")
    @classmethod
    def _uuid_to_str(cls, v: object) -> object:
        # project_id는 uuid 컬럼이라 asyncpg가 UUID 객체로 반환한다.
        return str(v) if v is not None and not isinstance(v, str) else v


class InMemoryMessageRepository:
    def __init__(self) -> None:
        self._store: dict[str, list[AgentMessage]] = {}

    async def append(
        self,
        thread_id: str,
        role: str,
        content: str,
        *,
        project_id: str | None = None,
        run_id: str | None = None,
    ) -> AgentMessage:
        message = AgentMessage(
            id=str(uuid4()),
            thread_id=thread_id,
            project_id=project_id,
            role=role,
            content=content,
            run_id=run_id,
            created_at=datetime.now(timezone.utc),
        )
        self._store.setdefault(thread_id, []).append(message)
        return message

    async def list_by_thread(self, thread_id: str, *, limit: int = 50) -> list[AgentMessage]:
        messages = sorted(
            self._store.get(thread_id, []),
            key=lambda m: m.created_at or datetime.min.replace(tzinfo=timezone.utc),
        )
        # 가장 최근 limit개만(시간순 유지). 0/음수는 빈 list.
        return messages[-limit:] if limit > 0 else []


class PostgresMessageRepository:
    def __init__(self, pool: asyncpg.Pool | None = None) -> None:
        self._pool = pool

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def append(
        self,
        thread_id: str,
        role: str,
        content: str,
        *,
        project_id: str | None = None,
        run_id: str | None = None,
    ) -> AgentMessage:
        message_id = str(uuid4())
        async with self._get_pool().acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO agent_message (id, thread_id, project_id, role, content, run_id)
                VALUES ($1::uuid, $2, $3::uuid, $4, $5, $6)
                RETURNING *
                """,
                message_id,
                thread_id,
                project_id,
                role,
                content,
                run_id,
            )
        return _row_to_message(row)

    async def list_by_thread(self, thread_id: str, *, limit: int = 50) -> list[AgentMessage]:
        if limit <= 0:
            return []
        async with self._get_pool().acquire() as conn:
            # 최신 limit개를 잡아 다시 시간순으로 정렬한다.
            rows = await conn.fetch(
                """
                SELECT * FROM (
                    SELECT * FROM agent_message
                    WHERE thread_id = $1
                    ORDER BY created_at DESC
                    LIMIT $2
                ) t
                ORDER BY created_at ASC
                """,
                thread_id,
                min(limit, 200),
            )
        return [_row_to_message(row) for row in rows]


AnyMessageRepo = Union[InMemoryMessageRepository, PostgresMessageRepository]

_memory_repo = InMemoryMessageRepository()
_postgres_repo = PostgresMessageRepository()


def get_message_repo() -> AnyMessageRepo:
    from app.core.db import _pool

    if _pool is None:
        return _memory_repo
    return _postgres_repo


def _row_to_message(row: asyncpg.Record | dict[str, Any]) -> AgentMessage:
    data = dict(row)
    return AgentMessage(
        id=str(data["id"]),
        thread_id=data["thread_id"],
        project_id=data.get("project_id"),
        role=data["role"],
        content=data["content"],
        run_id=data.get("run_id"),
        created_at=data.get("created_at"),
    )
