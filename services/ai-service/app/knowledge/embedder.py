"""Embedding providers for the Knowledge Vector Store.

Production uses OpenAI embeddings when an API key is configured. Tests and local
development without secrets use a deterministic hashing embedder so indexing and
search code remains executable without network access.
"""
from __future__ import annotations

import hashlib
import math
import os
import re
from typing import Protocol

from app.core.config import settings


class EmbeddingConfigurationError(RuntimeError):
    """Raised when a requested embedding provider cannot be configured."""


class Embedder(Protocol):
    dimensions: int

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        """Return one embedding vector per input text."""

    async def embed_text(self, text: str) -> list[float]:
        """Return one embedding vector for a single input text."""


_TOKEN_RE = re.compile(r"[a-z0-9_+#./:-]+|[가-힣]+", re.IGNORECASE)


def resolve_embedding_api_key(explicit_api_key: str | None = None) -> str:
    """Resolve the API key with deployment-friendly precedence.

    Precedence: explicit argument → AI_EMBEDDING_API_KEY/settings →
    AI_LLM_API_KEY/settings → OPENAI_API_KEY. The last fallback keeps the
    service compatible with standard OpenAI deployments that already export
    OPENAI_API_KEY outside the AI_ prefix.
    """
    return (
        explicit_api_key
        or settings.embedding_api_key
        or settings.llm_api_key
        or os.getenv("OPENAI_API_KEY", "")
    ).strip()


class OpenAIEmbedder:
    """Async OpenAI embeddings client wrapper."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        model: str | None = None,
        dimensions: int | None = None,
    ) -> None:
        resolved_key = resolve_embedding_api_key(api_key)
        if not resolved_key:
            raise EmbeddingConfigurationError(
                "OpenAI embeddings require AI_EMBEDDING_API_KEY, AI_LLM_API_KEY, or OPENAI_API_KEY"
            )

        try:
            from openai import AsyncOpenAI
        except ImportError as exc:  # pragma: no cover - depends on deployment image
            raise EmbeddingConfigurationError("openai package is not installed") from exc

        self.model = model or settings.embedding_model
        self.dimensions = dimensions or settings.embedding_dimensions
        self._client = AsyncOpenAI(api_key=resolved_key)

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        payload: dict[str, object] = {"model": self.model, "input": texts}
        # OpenAI text-embedding-3 models support dimension shortening. Older
        # embedding models reject this option, so only send it for 3-series IDs.
        if self.dimensions and self.model.startswith("text-embedding-3"):
            payload["dimensions"] = self.dimensions

        response = await self._client.embeddings.create(**payload)
        return [list(item.embedding) for item in response.data]

    async def embed_text(self, text: str) -> list[float]:
        return (await self.embed_texts([text]))[0]


class HashingEmbedder:
    """Deterministic local embedder for tests/dev without external API access.

    This is not a semantic replacement for OpenAI embeddings. It preserves token
    overlap well enough for local idempotency tests and simple glossary lookups
    while keeping production behavior keyed to OpenAI when credentials exist.
    """

    def __init__(self, dimensions: int | None = None) -> None:
        self.dimensions = dimensions or settings.embedding_dimensions
        if self.dimensions <= 0:
            raise ValueError("embedding dimensions must be positive")

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [self._embed(text) for text in texts]

    async def embed_text(self, text: str) -> list[float]:
        return self._embed(text)

    def _embed(self, text: str) -> list[float]:
        vector = [0.0] * self.dimensions
        tokens = _tokenize(text)
        if not tokens:
            return vector

        for token in tokens:
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:8], "big") % self.dimensions
            sign = 1.0 if digest[8] % 2 == 0 else -1.0
            # Damp repeated long content while preserving exact token overlap.
            vector[index] += sign / math.sqrt(len(tokens))

        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0.0:
            return vector
        return [value / norm for value in vector]


def _tokenize(text: str) -> list[str]:
    return [match.group(0).lower() for match in _TOKEN_RE.finditer(text)]


def get_embedder(*, prefer_openai: bool = True) -> Embedder:
    """Return the configured embedder.

    If no OpenAI-compatible API key is present, fall back to deterministic local
    embeddings so offline tests and local indexing remain usable.
    """
    if prefer_openai and resolve_embedding_api_key():
        return OpenAIEmbedder()
    return HashingEmbedder()


async def embed_texts(texts: list[str], embedder: Embedder | None = None) -> list[list[float]]:
    provider = embedder or get_embedder()
    return await provider.embed_texts(texts)


async def embed_text(text: str, embedder: Embedder | None = None) -> list[float]:
    provider = embedder or get_embedder()
    return await provider.embed_text(text)
