"""Offline indexer for curated RAG knowledge.

Indexes the built-in runbook catalog and a small operations glossary into
knowledge_chunk. The generated chunk_id is deterministic, so rerunning the batch
updates existing rows rather than duplicating corpus entries.
"""
from __future__ import annotations

import asyncio
import hashlib
from dataclasses import dataclass, field
from typing import Any, Iterable, Sequence
from uuid import UUID, uuid5

from app.catalogs import runbooks as runbook_catalog
from app.core.config import settings
from app.knowledge.embedder import Embedder, get_embedder
from app.knowledge.vector_store import GLOBAL_SCOPE, KnowledgeChunk, PostgresVectorStore, get_vector_store


_INDEX_NAMESPACE = UUID("6d2b682a-82e6-4a57-9df1-e9f6b17b6e22")
_DEFAULT_CHUNK_CHARS = 1400
_DEFAULT_OVERLAP_CHARS = 160


@dataclass(frozen=True)
class SourceDocument:
    doc_id: str
    doc_type: str
    title: str
    content: str
    scope: str = GLOBAL_SCOPE
    metadata: dict[str, Any] = field(default_factory=dict)


_GLOSSARY: tuple[SourceDocument, ...] = (
    SourceDocument(
        doc_id="glossary:dlq",
        doc_type="glossary",
        title="DLQ (Dead Letter Queue)",
        content=(
            "DLQ(Dead Letter Queue)는 처리에 실패한 메시지를 정상 처리 흐름과 분리해 보관하는 큐다. "
            "Kafka Connect나 consumer 처리 중 변환, 직렬화, 외부 시스템 쓰기 실패가 반복될 때 실패 레코드를 "
            "별도 topic 또는 큐에 격리해 원본 파이프라인 중단을 줄이고 이후 재처리·분석할 수 있게 한다. "
            "운영 중 DLQ가 증가하면 실패 원인 로그, connector task 상태, consumer lag, 최근 배포 변경을 함께 확인한다."
        ),
        metadata={"tags": ["kafka", "dlq", "dead-letter-queue"], "source": "built-in-glossary"},
    ),
    SourceDocument(
        doc_id="glossary:consumer-lag",
        doc_type="glossary",
        title="Consumer Lag",
        content=(
            "Consumer lag는 Kafka topic에 쌓인 최신 offset과 consumer group이 처리한 offset의 차이다. "
            "lag가 계속 증가하면 처리량 부족, downstream 장애, consumer 재시작 루프, partition skew를 의심한다."
        ),
        metadata={"tags": ["kafka", "consumer", "lag"], "source": "built-in-glossary"},
    ),
    SourceDocument(
        doc_id="glossary:kafka-connect-task",
        doc_type="glossary",
        title="Kafka Connect Task",
        content=(
            "Kafka Connect task는 connector 작업을 실제로 수행하는 실행 단위다. "
            "connector가 RUNNING이어도 일부 task가 FAILED면 데이터 이동이 부분 중단될 수 있으므로 task별 상태와 trace를 확인해야 한다."
        ),
        metadata={"tags": ["kafka-connect", "task"], "source": "built-in-glossary"},
    ),
)


def default_source_documents() -> list[SourceDocument]:
    """Return built-in runbook and glossary documents for indexing."""
    return [*_runbook_documents(), *_GLOSSARY]


async def build_chunks(
    documents: Sequence[SourceDocument],
    *,
    embedder: Embedder | None = None,
    doc_version: str | None = None,
    chunk_chars: int = _DEFAULT_CHUNK_CHARS,
    overlap_chars: int = _DEFAULT_OVERLAP_CHARS,
) -> list[KnowledgeChunk]:
    """Build deterministic, embedded chunks from source documents."""
    if chunk_chars <= 0:
        raise ValueError("chunk_chars must be positive")
    if overlap_chars < 0 or overlap_chars >= chunk_chars:
        raise ValueError("overlap_chars must be non-negative and smaller than chunk_chars")

    version = doc_version or settings.catalog_version
    chunk_specs: list[tuple[SourceDocument, int, str]] = []
    for document in documents:
        for index, content in enumerate(_split_content(document.content, chunk_chars, overlap_chars)):
            chunk_specs.append((document, index, content))

    if not chunk_specs:
        return []

    provider = embedder or get_embedder()
    embeddings = await provider.embed_texts([
        f"{document.title}\n{content}" for document, _, content in chunk_specs
    ])

    chunks: list[KnowledgeChunk] = []
    for (document, chunk_index, content), embedding in zip(chunk_specs, embeddings, strict=True):
        metadata = {
            **document.metadata,
            "chunk_index": chunk_index,
            "content_hash": _content_hash(content),
        }
        chunks.append(
            KnowledgeChunk(
                chunk_id=_chunk_id(document, version, chunk_index),
                doc_id=document.doc_id,
                doc_type=document.doc_type,
                title=document.title,
                content=content,
                embedding=embedding,
                scope=document.scope,
                doc_version=version,
                metadata=metadata,
            )
        )
    return chunks


async def build_default_chunks(
    *,
    embedder: Embedder | None = None,
    doc_version: str | None = None,
) -> list[KnowledgeChunk]:
    return await build_chunks(default_source_documents(), embedder=embedder, doc_version=doc_version)


async def index_default_corpus(
    *,
    vector_store: PostgresVectorStore | None = None,
    embedder: Embedder | None = None,
    doc_version: str | None = None,
) -> int:
    """Build and upsert the built-in corpus. Safe to rerun."""
    store = vector_store or get_vector_store()
    chunks = await build_default_chunks(embedder=embedder, doc_version=doc_version)
    return await store.upsert_chunks(chunks)


async def index_document(
    *,
    doc_id: str,
    doc_type: str,
    title: str,
    content: str,
    scope: str = GLOBAL_SCOPE,
    doc_version: str | None = None,
    metadata: dict[str, Any] | None = None,
    embedder: Embedder | None = None,
    store: PostgresVectorStore | None = None,
) -> int:
    """Build and upsert chunks for one external source document."""
    vector_store = store or get_vector_store()
    chunks = await build_chunks(
        [
            SourceDocument(
                doc_id=doc_id,
                doc_type=doc_type,
                title=title,
                content=content,
                scope=scope,
                metadata=metadata or {},
            )
        ],
        embedder=embedder,
        doc_version=doc_version,
    )
    return await vector_store.upsert_chunks(chunks)


def _runbook_documents() -> list[SourceDocument]:
    documents: list[SourceDocument] = []
    for runbook in runbook_catalog.ROOT_CAUSE_RUNBOOKS:
        tags = ["runbook", runbook.root_cause_id]
        action_lines = []
        for action in runbook.actions:
            if action.tool_name:
                tags.append(action.tool_name)
            action_lines.append(
                "\n".join(
                    part for part in [
                        f"- action: {action.action_name}",
                        f"  action_type: {action.action_type}",
                        f"  risk: {action.risk}",
                        f"  policy: {action.policy}",
                        f"  tool: {action.tool_name}" if action.tool_name else "",
                        f"  description: {action.description}",
                        f"  rollback: {action.rollback_plan}" if action.rollback_plan else "",
                        f"  estimated_duration: {action.estimated_duration}" if action.estimated_duration else "",
                    ]
                    if part
                )
            )

        action_body = "\n".join(action_lines) or "(권장 조치 없음 — 에스컬레이션/근거 수집만)"
        content_parts = [
            f"root_cause_id={runbook.root_cause_id}",
            f"disposition={runbook.disposition}",
            f"allowed_action_types={', '.join(runbook.allowed_action_types)}",
            f"basis: {runbook.basis}",
            "권장 조치 후보:",
            action_body,
        ]
        if runbook.forbidden_actions:
            content_parts.append("금지 조치: " + ", ".join(runbook.forbidden_actions))

        documents.append(
            SourceDocument(
                doc_id=f"runbook:{runbook.root_cause_id}",
                doc_type="runbook",
                title=f"Runbook: {runbook.root_cause_id}",
                content="\n".join(content_parts),
                metadata={"tags": tags, "source": "app.catalogs.runbooks"},
            )
        )
    return documents


def _split_content(content: str, chunk_chars: int, overlap_chars: int) -> Iterable[str]:
    normalized = "\n".join(line.rstrip() for line in content.strip().splitlines()).strip()
    if not normalized:
        return []
    if len(normalized) <= chunk_chars:
        return [normalized]

    chunks: list[str] = []
    start = 0
    while start < len(normalized):
        end = min(start + chunk_chars, len(normalized))
        if end < len(normalized):
            split_at = normalized.rfind("\n", start, end)
            if split_at <= start:
                split_at = normalized.rfind(" ", start, end)
            if split_at > start:
                end = split_at
        chunks.append(normalized[start:end].strip())
        if end >= len(normalized):
            break
        start = max(0, end - overlap_chars)
    return [chunk for chunk in chunks if chunk]


def _chunk_id(document: SourceDocument, doc_version: str, chunk_index: int) -> UUID:
    return uuid5(_INDEX_NAMESPACE, f"{document.scope}:{document.doc_id}:{doc_version}:{chunk_index}")


def _content_hash(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]


async def _amain() -> None:
    count = await index_default_corpus()
    print(f"indexed {count} knowledge chunks")


if __name__ == "__main__":  # pragma: no cover - manual batch entrypoint
    asyncio.run(_amain())
