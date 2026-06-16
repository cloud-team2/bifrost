from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.api import routes_health
from app.knowledge.vector_store import PostgresVectorStore
from app.llm.provider import LLMProvider


class _Provider:
    def __init__(self, result=True, *, delay: float = 0.0, error: Exception | None = None) -> None:
        self.result = result
        self.delay = delay
        self.error = error

    async def health(self) -> bool:
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return self.result


class _Registry:
    def __init__(self, result=True) -> None:
        self.result = result

    async def health(self) -> bool:
        return self.result


class _VectorStore:
    """Fake vector store whose readiness is driven by chunk count."""

    def __init__(self, count: int = 0, *, delay: float = 0.0, error: Exception | None = None) -> None:
        self._count = count
        self.delay = delay
        self.error = error

    async def count(self) -> int:
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return self._count


def _pool(execute_return: str = "SELECT 1"):
    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=execute_return)

    pool = MagicMock()
    pool.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
    pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)
    return pool, conn


async def _ready_dependencies(monkeypatch, *, llm=None, vector=None, spring=True, db_pool=None):
    import app.core.db as db

    monkeypatch.setattr(db, "_pool", db_pool)
    monkeypatch.setattr(routes_health, "get_tool_registry", lambda: _Registry(spring))
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: llm or _Provider())
    monkeypatch.setattr("app.knowledge.vector_store.get_vector_store", lambda: vector or _VectorStore(1))

    response = await routes_health.ready()
    return response.data["dependencies"]


@pytest.mark.asyncio
async def test_ready_llm_provider_ok(monkeypatch) -> None:
    dependencies = await _ready_dependencies(monkeypatch, llm=_Provider(True))

    assert dependencies["llm_provider"] == "ok"


@pytest.mark.asyncio
async def test_ready_llm_provider_unavailable_on_exception(monkeypatch) -> None:
    dependencies = await _ready_dependencies(monkeypatch, llm=_Provider(error=RuntimeError("boom")))

    assert dependencies["llm_provider"] == "unavailable"


@pytest.mark.asyncio
async def test_ready_vector_store_ok_when_corpus_populated(monkeypatch) -> None:
    dependencies = await _ready_dependencies(monkeypatch, vector=_VectorStore(42))

    assert dependencies["vector_store"] == "ok"


@pytest.mark.asyncio
async def test_ready_vector_store_empty_when_unseeded(monkeypatch) -> None:
    # Regression: a reachable but empty knowledge_chunk used to report "ok",
    # hiding that RAG had no corpus. Now it must surface as "empty".
    dependencies = await _ready_dependencies(monkeypatch, vector=_VectorStore(0))

    assert dependencies["vector_store"] == "empty"


@pytest.mark.asyncio
async def test_ready_vector_store_unavailable_on_error(monkeypatch) -> None:
    dependencies = await _ready_dependencies(
        monkeypatch, vector=_VectorStore(error=RuntimeError("db down"))
    )

    assert dependencies["vector_store"] == "unavailable"


@pytest.mark.asyncio
async def test_ready_db_pool_none_returns_unknown(monkeypatch) -> None:
    dependencies = await _ready_dependencies(monkeypatch, db_pool=None)

    assert dependencies["agent_run_store"] == "unknown"


@pytest.mark.asyncio
async def test_ready_timeout_returns_unavailable(monkeypatch) -> None:
    original_status = routes_health._vector_store_status

    async def fast_status(timeout: float = 2.0) -> tuple[str, int]:
        return await original_status(timeout=0.001)

    monkeypatch.setattr(routes_health, "_vector_store_status", fast_status)

    dependencies = await _ready_dependencies(monkeypatch, vector=_VectorStore(1, delay=0.05))

    assert dependencies["vector_store"] == "unavailable"


@pytest.mark.asyncio
async def test_ready_evidence_store_piggybacks_spring(monkeypatch) -> None:
    dependencies = await _ready_dependencies(monkeypatch, spring=False)

    assert dependencies["spring_operations"] == "unavailable"
    assert dependencies["evidence_store"] == "unavailable"


@pytest.mark.asyncio
async def test_llm_health_caches_for_5_minutes(monkeypatch) -> None:
    provider = LLMProvider()
    client = MagicMock()
    client.models.list = AsyncMock(return_value=[])
    provider._client = client

    monkeypatch.setattr("app.llm.provider.time.time", lambda: 1000.0)
    assert await provider.health() is True

    client.models.list.side_effect = RuntimeError("network down")
    monkeypatch.setattr("app.llm.provider.time.time", lambda: 1200.0)
    assert await provider.health() is True
    assert client.models.list.await_count == 1


@pytest.mark.asyncio
async def test_vector_store_health_pings_knowledge_chunk() -> None:
    pool, conn = _pool()
    store = PostgresVectorStore(pool=pool, embedder=MagicMock())

    assert await store.health() is True
    conn.execute.assert_awaited_once_with("SELECT 1 FROM knowledge_chunk LIMIT 1")
