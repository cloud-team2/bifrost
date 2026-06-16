from __future__ import annotations

import pytest

from app.catalogs import runbooks as runbook_catalog
from app.knowledge.embedder import HashingEmbedder
from app.knowledge.indexer import build_default_chunks, default_source_documents, index_default_corpus


class FakeVectorStore:
    def __init__(self) -> None:
        self.chunks = []

    async def upsert_chunks(self, chunks):
        self.chunks = list(chunks)
        return len(chunks)


def test_default_documents_include_dlq_glossary() -> None:
    docs = default_source_documents()

    dlq = next(doc for doc in docs if doc.doc_id == "glossary:dlq")
    assert dlq.doc_type == "glossary"
    assert "Dead Letter Queue" in dlq.title
    assert "실패" in dlq.content


def test_default_documents_index_every_runbook() -> None:
    # Regression: _runbook_documents() previously read a non-existent
    # `_RUNBOOKS` attribute and `runbook.incident_type` field, silently
    # indexing zero runbooks. Assert one doc per ROOT_CAUSE_RUNBOOKS entry.
    docs = default_source_documents()
    runbook_docs = [doc for doc in docs if doc.doc_type == "runbook"]

    assert len(runbook_docs) == len(runbook_catalog.ROOT_CAUSE_RUNBOOKS)
    expected_ids = {f"runbook:{rb.root_cause_id}" for rb in runbook_catalog.ROOT_CAUSE_RUNBOOKS}
    assert {doc.doc_id for doc in runbook_docs} == expected_ids

    sample = next(doc for doc in runbook_docs if doc.doc_id == "runbook:CONNECTOR_TASK_FAILED")
    assert "root_cause_id=CONNECTOR_TASK_FAILED" in sample.content
    assert "disposition=" in sample.content


@pytest.mark.asyncio
async def test_build_default_chunks_is_idempotent() -> None:
    embedder = HashingEmbedder(dimensions=32)

    first = await build_default_chunks(embedder=embedder, doc_version="test-version")
    second = await build_default_chunks(embedder=embedder, doc_version="test-version")

    assert [chunk.chunk_id for chunk in first] == [chunk.chunk_id for chunk in second]
    assert [chunk.metadata["content_hash"] for chunk in first] == [chunk.metadata["content_hash"] for chunk in second]
    assert any(chunk.doc_id == "glossary:dlq" for chunk in first)


@pytest.mark.asyncio
async def test_index_default_corpus_upserts_chunks() -> None:
    store = FakeVectorStore()

    count = await index_default_corpus(
        vector_store=store,
        embedder=HashingEmbedder(dimensions=32),
        doc_version="test-version",
    )

    assert count == len(store.chunks)
    assert count >= 3
