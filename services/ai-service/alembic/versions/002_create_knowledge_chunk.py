"""create knowledge_chunk pgvector store

Revision ID: 002
Revises: 001
Create Date: 2026-06-09

Ref: docs/design/backend-fastapi/server-design.md §9.3
"""
from __future__ import annotations

revision = "002"
down_revision = "001"
branch_labels = None
depends_on = None

from alembic import op


_VECTOR_DIMENSIONS = 1536


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute(f"""
        CREATE TABLE IF NOT EXISTS knowledge_chunk (
            chunk_id    uuid        PRIMARY KEY,
            doc_id      text        NOT NULL,
            doc_type    text        NOT NULL,
            title       text        NOT NULL,
            content     text        NOT NULL,
            embedding   vector({_VECTOR_DIMENSIONS}) NOT NULL,
            scope       text        NOT NULL DEFAULT 'global',
            doc_version text        NOT NULL,
            metadata    jsonb       NOT NULL DEFAULT '{{}}'::jsonb,
            updated_at  timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_scope_doc_type ON knowledge_chunk (scope, doc_type)")
    op.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_doc_version ON knowledge_chunk (doc_id, doc_version)")
    op.execute("""
        CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding_cosine
        ON knowledge_chunk
        USING ivfflat (embedding vector_cosine_ops)
        WITH (lists = 100)
    """)


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_knowledge_chunk_embedding_cosine")
    op.execute("DROP INDEX IF EXISTS idx_knowledge_chunk_doc_version")
    op.execute("DROP INDEX IF EXISTS idx_knowledge_chunk_scope_doc_type")
    op.execute("DROP TABLE IF EXISTS knowledge_chunk")
