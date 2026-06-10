"""pgvector-backed Knowledge Vector Store.

Stores curated RAG chunks in agentdb. Runtime evidence/raw logs stay outside this
store; only runbooks, glossary entries, docs, and curated report summaries are
saved here as described in server-design §9.3.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from typing import Any, Iterable, Sequence
from uuid import UUID

import asyncpg

from app.core.config import settings
from app.core.db import get_pool
from app.knowledge.embedder import Embedder, get_embedder


GLOBAL_SCOPE = "global"


@dataclass(frozen=True)
class KnowledgeChunk:
    chunk_id: UUID
    doc_id: str
    doc_type: str
    title: str
    content: str
    embedding: list[float]
    scope: str = GLOBAL_SCOPE
    doc_version: str = settings.catalog_version
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class KnowledgeSearchResult:
    chunk_id: str
    doc_id: str
    doc_type: str
    title: str
    content: str
    scope: str
    doc_version: str
    metadata: dict[str, Any]
    score: float

    @property
    def store_ref(self) -> str:
        return f"knowledge://{self.scope}/{self.doc_id}/{self.chunk_id}"

    def summary(self, max_content_chars: int = 500) -> str:
        content = self.content.strip()
        if len(content) > max_content_chars:
            content = f"{content[:max_content_chars].rstrip()}…"
        return f"[{self.doc_type}] {self.title}: {content}"


class PostgresVectorStore:
    def __init__(
        self,
        *,
        pool: asyncpg.Pool | None = None,
        embedder: Embedder | None = None,
    ) -> None:
        self._pool = pool
        self._embedder = embedder or get_embedder()

    def _get_pool(self) -> asyncpg.Pool:
        return self._pool or get_pool()

    async def health(self) -> bool:
        try:
            pool = self._get_pool()
            async with pool.acquire() as conn:
                await conn.execute("SELECT 1 FROM knowledge_chunk LIMIT 1")
            return True
        except Exception:
            return False

    async def upsert_chunks(self, chunks: Sequence[KnowledgeChunk]) -> int:
        """Insert/update chunks by deterministic chunk_id."""
        if not chunks:
            return 0

        rows = [
            (
                str(chunk.chunk_id),
                chunk.doc_id,
                chunk.doc_type,
                chunk.title,
                chunk.content,
                _to_vector_literal(chunk.embedding),
                chunk.scope,
                chunk.doc_version,
                json.dumps(chunk.metadata, ensure_ascii=False),
            )
            for chunk in chunks
        ]

        async with self._get_pool().acquire() as conn:
            await conn.executemany(
                """
                INSERT INTO knowledge_chunk
                    (chunk_id, doc_id, doc_type, title, content, embedding,
                     scope, doc_version, metadata, updated_at)
                VALUES ($1::uuid, $2, $3, $4, $5, $6::vector,
                        $7, $8, $9::jsonb, now())
                ON CONFLICT (chunk_id) DO UPDATE SET
                    doc_id = EXCLUDED.doc_id,
                    doc_type = EXCLUDED.doc_type,
                    title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    scope = EXCLUDED.scope,
                    doc_version = EXCLUDED.doc_version,
                    metadata = EXCLUDED.metadata,
                    updated_at = now()
                """,
                rows,
            )
        return len(chunks)

    async def search(
        self,
        query: str,
        *,
        project_id: str | None = None,
        scope: str | None = None,
        doc_types: Iterable[str] | None = None,
        limit: int = 5,
        min_score: float = 0.0,
    ) -> list[KnowledgeSearchResult]:
        embedding = await self._embedder.embed_text(query)
        return await self.search_by_embedding(
            embedding,
            project_id=project_id,
            scope=scope,
            doc_types=doc_types,
            limit=limit,
            min_score=min_score,
        )

    async def search_by_embedding(
        self,
        embedding: list[float],
        *,
        project_id: str | None = None,
        scope: str | None = None,
        doc_types: Iterable[str] | None = None,
        limit: int = 5,
        min_score: float = 0.0,
    ) -> list[KnowledgeSearchResult]:
        if limit <= 0:
            return []

        scopes = _search_scopes(project_id=project_id, explicit_scope=scope)
        doc_type_filter = list(doc_types) if doc_types else None
        vector = _to_vector_literal(embedding)

        async with self._get_pool().acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT
                    chunk_id::text AS chunk_id,
                    doc_id,
                    doc_type,
                    title,
                    content,
                    scope,
                    doc_version,
                    metadata,
                    1 - (embedding <=> $1::vector) AS score
                FROM knowledge_chunk
                WHERE scope = ANY($2::text[])
                  AND ($3::text[] IS NULL OR doc_type = ANY($3::text[]))
                  AND (1 - (embedding <=> $1::vector)) >= $4
                ORDER BY embedding <=> $1::vector
                LIMIT $5
                """,
                vector,
                scopes,
                doc_type_filter,
                min_score,
                limit,
            )
        return [_row_to_result(row) for row in rows]

    async def delete_scope(self, scope: str) -> int:
        """Delete all chunks in a scope and return the affected row count when available."""
        async with self._get_pool().acquire() as conn:
            status = await conn.execute("DELETE FROM knowledge_chunk WHERE scope = $1", scope)
        try:
            return int(status.rsplit(" ", 1)[-1])
        except (AttributeError, ValueError):
            return 0


class InMemoryVectorStore:
    async def health(self) -> bool:
        """InMemory 는 항상 ok (테스트/로컬용)."""
        return True


def get_vector_store() -> PostgresVectorStore:
    return PostgresVectorStore()


def project_scope(project_id: str) -> str:
    return f"project:{project_id}"


def _search_scopes(*, project_id: str | None, explicit_scope: str | None) -> list[str]:
    scopes = [GLOBAL_SCOPE]
    if project_id:
        scopes.append(project_scope(project_id))
    if explicit_scope:
        scopes.append(explicit_scope)
    return list(dict.fromkeys(scopes))


def _to_vector_literal(vector: Sequence[float]) -> str:
    if not vector:
        raise ValueError("embedding vector must not be empty")

    values: list[str] = []
    for raw_value in vector:
        value = float(raw_value)
        if not math.isfinite(value):
            raise ValueError("embedding vector contains non-finite value")
        values.append(repr(value))
    return f"[{','.join(values)}]"


def _row_to_result(row: asyncpg.Record | dict[str, Any]) -> KnowledgeSearchResult:
    data = dict(row)
    metadata = data.get("metadata") or {}
    if isinstance(metadata, str):
        metadata = json.loads(metadata)
    return KnowledgeSearchResult(
        chunk_id=str(data["chunk_id"]),
        doc_id=data["doc_id"],
        doc_type=data["doc_type"],
        title=data["title"],
        content=data["content"],
        scope=data["scope"],
        doc_version=data["doc_version"],
        metadata=metadata,
        score=float(data["score"]),
    )
