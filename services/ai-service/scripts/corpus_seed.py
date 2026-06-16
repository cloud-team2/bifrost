#!/usr/bin/env python3
"""Seed the external RAG corpus into the knowledge vector store."""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.knowledge.indexer import index_default_corpus, index_document
from app.knowledge.vector_store import GLOBAL_SCOPE


CORPUS_DIR = Path(__file__).resolve().parents[1] / "corpus"
_FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n?(.*)$", re.DOTALL)
_SIMPLE_LIST_RE = re.compile(r"^\[(.*)\]$")


def load_manifest(corpus_dir: Path = CORPUS_DIR) -> list[dict]:
    manifest_path = corpus_dir / "manifest.json"
    with manifest_path.open(encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, list):
        raise ValueError("manifest.json must contain a JSON array")
    return data


def parse_doc(path: Path) -> tuple[dict, str]:
    text = path.read_text(encoding="utf-8")
    match = _FRONTMATTER_RE.match(text)
    if not match:
        raise ValueError(f"missing YAML frontmatter: {path}")

    frontmatter_text, body = match.groups()
    frontmatter: dict[str, object] = {}
    current_key: str | None = None
    current_list: list[str] | None = None

    for raw_line in frontmatter_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("- ") and current_key and current_list is not None:
            current_list.append(line[2:].strip().strip("'\""))
            continue
        if ":" not in line:
            raise ValueError(f"invalid frontmatter line in {path}: {raw_line}")

        key, raw_value = line.split(":", 1)
        key = key.strip()
        value = raw_value.strip()
        current_key = key
        current_list = None

        if value == "":
            frontmatter[key] = []
            current_list = frontmatter[key]
            continue

        list_match = _SIMPLE_LIST_RE.match(value)
        if list_match:
            items = [
                item.strip().strip("'\"")
                for item in list_match.group(1).split(",")
                if item.strip()
            ]
            frontmatter[key] = items
        else:
            frontmatter[key] = value.strip("'\"")

    return frontmatter, body.strip()


async def seed_corpus(
    *,
    store=None,
    embedder=None,
    doc_version: str | None = None,
    dry_run: bool = False,
    corpus_dir: Path = CORPUS_DIR,
) -> dict:
    summary = {
        "docs": 0,
        "chunks": 0,
        "failed": 0,
        "by_doc_type": {},
        "details": [],
    }

    for item in load_manifest(corpus_dir):
        rel_path = item.get("path")
        path = corpus_dir / str(rel_path)
        try:
            frontmatter, body = parse_doc(path)
            _validate_manifest_item(item, frontmatter, path, corpus_dir)

            doc_type = str(frontmatter["doc_type"])
            summary["docs"] += 1
            summary["by_doc_type"][doc_type] = summary["by_doc_type"].get(doc_type, 0) + 1

            if dry_run:
                summary["details"].append(
                    {
                        "doc_id": frontmatter["doc_id"],
                        "doc_type": doc_type,
                        "path": rel_path,
                        "chunks": 0,
                        "status": "dry-run",
                    }
                )
                continue

            chunks = await index_document(
                doc_id=str(frontmatter["doc_id"]),
                doc_type=doc_type,
                title=str(frontmatter["title"]),
                content=body,
                scope=GLOBAL_SCOPE,
                doc_version=doc_version,
                metadata={
                    "tags": frontmatter.get("tags", []),
                    "source": frontmatter.get("source"),
                    "path": rel_path,
                },
                embedder=embedder,
                store=store,
            )
            summary["chunks"] += chunks
            summary["details"].append(
                {
                    "doc_id": frontmatter["doc_id"],
                    "doc_type": doc_type,
                    "path": rel_path,
                    "chunks": chunks,
                    "status": "ok",
                }
            )
        except Exception as exc:
            summary["failed"] += 1
            summary["details"].append(
                {
                    "doc_id": item.get("doc_id"),
                    "doc_type": item.get("doc_type"),
                    "path": rel_path,
                    "chunks": 0,
                    "status": "failed",
                    "error": str(exc),
                }
            )

    return summary


async def seed_all(*, store=None, embedder=None, doc_version: str | None = None) -> dict:
    default_chunks = await index_default_corpus(
        vector_store=store,
        embedder=embedder,
        doc_version=doc_version,
    )
    summary = await seed_corpus(store=store, embedder=embedder, doc_version=doc_version)
    summary["chunks"] += default_chunks
    summary["details"].insert(
        0,
        {
            "doc_id": "built-in",
            "doc_type": "built-in",
            "path": None,
            "chunks": default_chunks,
            "status": "ok",
        },
    )
    return summary


def _validate_manifest_item(item: dict, frontmatter: dict, path: Path, corpus_dir: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)

    required = {"doc_id", "doc_type", "title", "tags", "source"}
    missing = sorted(required - set(frontmatter))
    if missing:
        raise ValueError(f"missing frontmatter keys in {path}: {', '.join(missing)}")

    rel_path = Path(str(item.get("path", "")))
    parent_doc_type = rel_path.parent.name
    if frontmatter["doc_type"] != item.get("doc_type") or frontmatter["doc_type"] != parent_doc_type:
        raise ValueError(
            f"doc_type mismatch for {path.relative_to(corpus_dir)}: "
            f"frontmatter={frontmatter['doc_type']} manifest={item.get('doc_type')} "
            f"parent={parent_doc_type}"
        )

    if frontmatter["doc_id"] != item.get("doc_id"):
        raise ValueError(
            f"doc_id mismatch for {path.relative_to(corpus_dir)}: "
            f"frontmatter={frontmatter['doc_id']} manifest={item.get('doc_id')}"
        )

    if frontmatter["title"] != item.get("title"):
        raise ValueError(
            f"title mismatch for {path.relative_to(corpus_dir)}: "
            f"frontmatter={frontmatter['title']} manifest={item.get('title')}"
        )

    if frontmatter["source"] != item.get("source"):
        raise ValueError(
            f"source mismatch for {path.relative_to(corpus_dir)}: "
            f"frontmatter={frontmatter['source']} manifest={item.get('source')}"
        )

    tags = frontmatter.get("tags")
    if not isinstance(tags, list):
        raise ValueError(f"tags must be a list in {path.relative_to(corpus_dir)}")


def _resolve_corpus_dir(raw_corpus_dir: str) -> Path:
    path = Path(raw_corpus_dir)
    if path.is_absolute():
        return path.resolve()
    return (Path.cwd() / path).resolve()


async def _run_cli(args: argparse.Namespace) -> dict:
    if args.dry_run:
        return await seed_corpus(dry_run=True, corpus_dir=args.corpus_dir)

    from app.core.config import settings
    from app.core.db import close_pool, init_pool
    from app.knowledge.embedder import get_embedder
    from app.knowledge.vector_store import get_vector_store

    await init_pool(settings.database_url)
    try:
        return await seed_corpus(
            store=get_vector_store(),
            embedder=get_embedder(),
            corpus_dir=args.corpus_dir,
        )
    finally:
        await close_pool()


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed external RAG corpus documents.")
    parser.add_argument("--dry-run", action="store_true", help="parse and validate without DB writes")
    parser.add_argument("--scope", default=GLOBAL_SCOPE, help="knowledge scope; only global is supported")
    parser.add_argument("--corpus-dir", default=str(CORPUS_DIR), help="corpus directory")
    args = parser.parse_args()
    args.corpus_dir = _resolve_corpus_dir(args.corpus_dir)

    if args.scope != GLOBAL_SCOPE:
        print(f"only scope={GLOBAL_SCOPE!r} is supported by corpus_seed.py", file=sys.stderr)
        return 1
    if not args.corpus_dir.is_dir():
        print(f"corpus dir not found: {args.corpus_dir}", file=sys.stderr)
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

    summary = asyncio.run(_run_cli(args))
    print_summary(summary)
    return 0 if summary["failed"] == 0 else 2


def print_summary(summary: dict) -> None:
    print("\n=== Summary ===")
    print(f"docs indexed: {summary['docs']}")
    print(f"chunks: {summary['chunks']}")
    print(f"failed: {summary['failed']}")
    print("\n--- By Doc Type ---")
    for doc_type, count in sorted(summary["by_doc_type"].items()):
        print(f"{doc_type}: {count}")
    print("\n--- Details ---")
    for detail in summary["details"]:
        status = detail["status"].upper()
        doc_id = detail.get("doc_id") or "<unknown>"
        chunks = detail.get("chunks", 0)
        line = f"{status}: {doc_id} ({detail.get('doc_type')}, {chunks} chunks)"
        if detail.get("error"):
            line = f"{line} - {detail['error']}"
        print(line)


if __name__ == "__main__":
    sys.exit(main())
