from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

import pytest

from app.knowledge.vector_store import (
    KnowledgeChunk,
    PostgresVectorStore,
    _to_vector_literal,
)


class FakeEmbedder:
    dimensions = 2

    async def embed_text(self, text: str) -> list[float]:
        return [1.0, 0.0]

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [[1.0, 0.0] for _ in texts]


def _make_pool(fetch_return=None, execute_return="DELETE 0"):
    conn = AsyncMock()
    conn.fetch = AsyncMock(return_value=fetch_return or [])
    conn.execute = AsyncMock(return_value=execute_return)
    conn.executemany = AsyncMock()

    pool = MagicMock()
    pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
    pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)
    return pool, conn


def test_to_vector_literal_validates_values() -> None:
    assert _to_vector_literal([1, 0.5, -0.25]) == "[1.0,0.5,-0.25]"
    with pytest.raises(ValueError):
        _to_vector_literal([])
    with pytest.raises(ValueError):
        _to_vector_literal([float("nan")])


@pytest.mark.asyncio
async def test_search_uses_pgvector_cosine_with_scope_filters() -> None:
    row = {
        "chunk_id": str(uuid4()),
        "doc_id": "glossary:dlq",
        "doc_type": "glossary",
        "title": "DLQ",
        "content": "Dead Letter Queue",
        "scope": "global",
        "doc_version": "0.1.0",
        "metadata": {"term": "DLQ"},
        "score": 0.91,
    }
    pool, conn = _make_pool(fetch_return=[row])
    store = PostgresVectorStore(pool=pool, embedder=FakeEmbedder())

    results = await store.search("DLQ가 뭐야?", project_id="proj-1", doc_types=["glossary"], limit=3)

    assert results[0].title == "DLQ"
    assert results[0].store_ref.startswith("knowledge://global/glossary:dlq/")
    args = conn.fetch.await_args.args
    assert args[1] == "[1.0,0.0]"
    assert args[2] == ["global", "project:proj-1"]
    assert args[3] == ["glossary"]
    assert args[5] == 3


@pytest.mark.asyncio
async def test_upsert_chunks_writes_vector_literal_and_metadata() -> None:
    pool, conn = _make_pool()
    store = PostgresVectorStore(pool=pool, embedder=FakeEmbedder())
    chunk = KnowledgeChunk(
        chunk_id=uuid4(),
        doc_id="runbook:connector_failed",
        doc_type="runbook",
        title="connector failed",
        content="restart connector",
        embedding=[0.25, -0.5],
        metadata={"source": "runbooks.py"},
    )

    count = await store.upsert_chunks([chunk])

    assert count == 1
    rows = conn.executemany.await_args.args[1]
    assert rows[0][5] == "[0.25,-0.5]"
    assert '"source": "runbooks.py"' in rows[0][8]
