#!/usr/bin/env python3
"""Seed knowledge_chunk from an allowlisted docs corpus.

Usage:
    AI_DATABASE_URL=... AI_LLM_API_KEY=... \
    python scripts/knowledge_seed.py [--docs-root docs] [--scope global] [--dry-run]
"""
from __future__ import annotations

import argparse
import asyncio
import os
import subprocess
import sys
from pathlib import Path
from typing import Any


ALLOWLIST_PATTERNS = [
    "docs/design/backend-fastapi/agent-principles.md",
    "docs/design/backend-fastapi/catalog/catalog-failure-types.md",
    "docs/design/backend-fastapi/catalog/catalog-root-causes.md",
    "docs/design/backend-fastapi/catalog/catalog-remediation-runbooks.md",
    "docs/design/backend-fastapi/catalog/catalog-evidence-matrix.md",
    "docs/design/backend-fastapi/catalog/catalog-policy-matrix.md",
    "docs/design/backend-fastapi/contract/contract-agent-roles.md",
    "docs/design/backend-springboot/monitoring.md",
]

DOC_TYPE_BY_PATH_PREFIX = [
    ("docs/design/backend-fastapi/catalog/", "catalog"),
    ("docs/design/backend-fastapi/contract/", "ops_doc"),
    ("docs/design/backend-fastapi/agent-principles.md", "ops_doc"),
    ("docs/design/backend-springboot/", "ops_doc"),
]


def resolve_doc_type(rel_path: str) -> str:
    for prefix, doc_type in DOC_TYPE_BY_PATH_PREFIX:
        if rel_path.startswith(prefix):
            return doc_type
    return "ops_doc"


def git_short_sha(repo_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        return result.stdout.strip() or "unversioned"
    except Exception:
        return "unversioned"


async def _index_documents(
    docs_root: Path,
    scope: str,
    dry_run: bool,
    repo_root: Path,
    *,
    reset: bool = False,
    include_builtin: bool = True,
) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "docs": 0,
        "chunks": 0,
        "failed": 0,
        "skipped": 0,
        "deleted": 0,
        "details": [],
    }
    doc_version = git_short_sha(repo_root)

    if not dry_run:
        service_root = repo_root / "services" / "ai-service"
        sys.path.insert(0, str(service_root))

        from app.core.config import settings
        from app.core.db import close_pool, init_pool
        from app.knowledge.embedder import get_embedder
        from app.knowledge.indexer import index_default_corpus, index_document
        from app.knowledge.vector_store import get_vector_store

        await init_pool(settings.database_url)
        embedder = get_embedder()
        store = get_vector_store()
    else:
        close_pool = None
        embedder = None
        index_default_corpus = None
        index_document = None
        store = None

    try:
        # Re-deploys bump doc_version to the new git SHA, so prior chunks linger
        # under their old version. Reset the scope first to keep the corpus clean.
        if reset and not dry_run:
            deleted = await store.delete_scope(scope)
            summary["deleted"] = deleted
            summary["details"].append(f"RESET: deleted {deleted} chunks in scope={scope}")
        elif reset:
            summary["details"].append(f"DRY-RUN RESET: would delete scope={scope}")

        # Built-in corpus (glossary + runbook) lives in code, not docs/. Index it
        # in the same run so a single entrypoint produces the full corpus.
        if include_builtin:
            if dry_run:
                summary["details"].append("DRY-RUN: built-in glossary + runbook corpus")
            else:
                builtin_count = await index_default_corpus(
                    vector_store=store,
                    embedder=embedder,
                    doc_version=doc_version,
                )
                summary["chunks"] += builtin_count
                summary["details"].append(
                    f"OK: built-in glossary + runbook ({builtin_count} chunks)"
                )

        for rel_path in ALLOWLIST_PATTERNS:
            path = docs_root / rel_path.removeprefix("docs/")
            if not path.is_file():
                summary["skipped"] += 1
                summary["details"].append(f"SKIP (not found): {rel_path}")
                continue

            content = path.read_text(encoding="utf-8")
            title = next(
                (line[2:].strip() for line in content.splitlines() if line.startswith("# ")),
                path.stem,
            )
            doc_type = resolve_doc_type(rel_path)

            if dry_run:
                summary["docs"] += 1
                chunks = max(1, (len(content) + 999) // 1000)
                summary["chunks"] += chunks
                summary["details"].append(
                    f"DRY-RUN: {rel_path} ({doc_type}, ~{chunks} chunks)"
                )
                continue

            try:
                chunk_count = await index_document(
                    doc_id=rel_path,
                    doc_type=doc_type,
                    title=title,
                    content=content,
                    scope=scope,
                    doc_version=doc_version,
                    metadata={"source_path": rel_path},
                    embedder=embedder,
                    store=store,
                )
                summary["docs"] += 1
                summary["chunks"] += chunk_count
                summary["details"].append(f"OK: {rel_path} ({chunk_count} chunks)")
            except Exception as exc:
                summary["failed"] += 1
                summary["details"].append(f"FAIL: {rel_path} - {exc}")
    finally:
        if close_pool is not None:
            await close_pool()

    return summary


def _resolve_docs_root(raw_docs_root: str) -> Path:
    path = Path(raw_docs_root)
    if path.is_absolute():
        return path.resolve()
    return (Path.cwd() / path).resolve()


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed knowledge_chunk from docs.")
    parser.add_argument("--docs-root", default="docs", help="docs root directory")
    parser.add_argument("--scope", default="global", help="knowledge_chunk scope")
    parser.add_argument("--dry-run", action="store_true", help="no DB writes")
    parser.add_argument(
        "--reset",
        action="store_true",
        help="delete existing chunks in scope before seeding (clears stale doc_versions)",
    )
    parser.add_argument(
        "--no-builtin",
        dest="include_builtin",
        action="store_false",
        help="skip the built-in glossary + runbook corpus (docs allowlist only)",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    docs_root = _resolve_docs_root(args.docs_root)
    if not docs_root.is_dir():
        print(f"docs root not found: {docs_root}", file=sys.stderr)
        return 1

    if not args.dry_run:
        if not os.environ.get("AI_DATABASE_URL"):
            print("AI_DATABASE_URL required (or use --dry-run)", file=sys.stderr)
            return 1
        if not (
            os.environ.get("AI_EMBEDDING_API_KEY")
            or os.environ.get("AI_LLM_API_KEY")
            or os.environ.get("OPENAI_API_KEY")
        ):
            print(
                "AI_EMBEDDING_API_KEY, AI_LLM_API_KEY, or OPENAI_API_KEY required "
                "(or use --dry-run)",
                file=sys.stderr,
            )
            return 1

    summary = asyncio.run(
        _index_documents(
            docs_root,
            args.scope,
            args.dry_run,
            repo_root,
            reset=args.reset,
            include_builtin=args.include_builtin,
        )
    )

    print("\n=== Summary ===")
    print(f"docs indexed: {summary['docs']}")
    print(f"chunks: {summary['chunks']}")
    print(f"deleted (reset): {summary['deleted']}")
    print(f"failed: {summary['failed']}")
    print(f"skipped: {summary['skipped']}")
    print("\n--- Details ---")
    for line in summary["details"]:
        print(line)

    return 0 if summary["failed"] == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
