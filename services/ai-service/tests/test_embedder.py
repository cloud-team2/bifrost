from __future__ import annotations

import pytest

from app.knowledge.embedder import HashingEmbedder, resolve_embedding_api_key


@pytest.mark.asyncio
async def test_hashing_embedder_is_deterministic_and_normalized() -> None:
    embedder = HashingEmbedder(dimensions=32)

    first = await embedder.embed_text("DLQ dead letter queue")
    second = await embedder.embed_text("DLQ dead letter queue")

    assert first == second
    assert len(first) == 32
    assert sum(value * value for value in first) == pytest.approx(1.0)


def test_resolve_embedding_api_key_precedence(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.core.config import settings

    monkeypatch.setattr(settings, "embedding_api_key", "")
    monkeypatch.setattr(settings, "llm_api_key", "")
    monkeypatch.setenv("OPENAI_API_KEY", "openai-env-key")

    assert resolve_embedding_api_key() == "openai-env-key"
    assert resolve_embedding_api_key("explicit") == "explicit"
