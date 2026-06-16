from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import corpus_seed
from app.knowledge.embedder import HashingEmbedder


VALID_ROOT_CAUSE_IDS = {
    "CONNECTOR_TASK_FAILED",
    "CONSUMER_LAG_SPIKE",
    "SOURCE_AUTH_EXPIRED",
    "SOURCE_NETWORK_REACHABILITY",
    "SCHEMA_MISMATCH",
    "SINK_AUTH_EXPIRED",
}


class FakeVectorStore:
    def __init__(self) -> None:
        self.chunks = []
        self.records = []

    async def upsert_chunks(self, chunks):
        self.chunks.extend(chunks)
        for chunk in chunks:
            self.records.append((chunk.doc_id, chunk.doc_type))
        return len(chunks)


def test_manifest_paths_and_doctype_consistency():
    manifest = corpus_seed.load_manifest()

    for item in manifest:
        path = corpus_seed.CORPUS_DIR / item["path"]
        assert path.is_file(), item["path"]

        frontmatter, _ = corpus_seed.parse_doc(path)
        assert frontmatter["doc_type"] == item["doc_type"]
        assert frontmatter["doc_type"] == Path(item["path"]).parent.name
        assert frontmatter["doc_id"] == item["doc_id"]


@pytest.mark.asyncio
async def test_seed_corpus_counts():
    store = FakeVectorStore()
    summary = await corpus_seed.seed_corpus(
        store=store,
        embedder=HashingEmbedder(dimensions=32),
    )

    assert summary["failed"] == 0
    assert summary["docs"] == len(corpus_seed.load_manifest())
    assert summary["chunks"] == len(store.chunks)
    assert summary["by_doc_type"]["incident_report"] >= 12
    assert summary["by_doc_type"]["glossary"] >= 13
    assert summary["by_doc_type"]["catalog"] >= 7
    assert summary["by_doc_type"]["ops_doc"] >= 5


def test_incident_reports_reference_valid_root_causes():
    manifest = corpus_seed.load_manifest()
    incident_items = [item for item in manifest if item["doc_type"] == "incident_report"]
    assert len(incident_items) == 12

    for item in incident_items:
        _, body = corpus_seed.parse_doc(corpus_seed.CORPUS_DIR / item["path"])
        mentioned = {root for root in VALID_ROOT_CAUSE_IDS if root in body}
        assert len(mentioned) == 1, item["path"]


@pytest.mark.asyncio
async def test_seed_all_includes_builtin_runbook():
    # The manifest has no runbook doc_type; runbook only comes from the built-in
    # index_default_corpus(). The deploy Job runs --with-builtin (seed_all) so the
    # runbook doc_type is populated and acceptance (runbook >= 1) holds.
    store = FakeVectorStore()
    summary = await corpus_seed.seed_all(
        store=store,
        embedder=HashingEmbedder(dimensions=32),
    )

    assert summary["failed"] == 0
    seeded_doc_types = {doc_type for _, doc_type in store.records}
    assert "runbook" in seeded_doc_types
    assert {"glossary", "catalog", "ops_doc", "incident_report"} <= seeded_doc_types
    assert summary["chunks"] == len(store.chunks)

    # seed_all must add built-in chunks on top of the manifest-only corpus.
    corpus_only = await corpus_seed.seed_corpus(
        store=FakeVectorStore(),
        embedder=HashingEmbedder(dimensions=32),
    )
    assert summary["chunks"] > corpus_only["chunks"]


@pytest.mark.asyncio
async def test_dry_run_matches_real_count():
    dry_run = await corpus_seed.seed_corpus(dry_run=True)
    real = await corpus_seed.seed_corpus(
        store=FakeVectorStore(),
        embedder=HashingEmbedder(dimensions=32),
    )

    assert dry_run["failed"] == 0
    assert real["failed"] == 0
    assert dry_run["docs"] == real["docs"]
    assert dry_run["by_doc_type"] == real["by_doc_type"]
