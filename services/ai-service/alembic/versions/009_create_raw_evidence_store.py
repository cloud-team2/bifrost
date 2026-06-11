"""create raw evidence store

Revision ID: 009
Revises: 008
Create Date: 2026-06-11

Stores redacted raw evidence payloads referenced by State evidence metadata.
"""
from __future__ import annotations

from alembic import op

revision = "009"
down_revision = "008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS raw_evidence (
            store_ref        text        PRIMARY KEY,
            run_id           text        NOT NULL REFERENCES agent_run(run_id),
            evidence_id      text        NOT NULL,
            tool_name        text,
            step_id          text,
            status           text        NOT NULL,
            payload          jsonb       NOT NULL,
            redaction_status text        NOT NULL DEFAULT 'redacted',
            created_at       timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_raw_evidence_run ON raw_evidence (run_id, created_at ASC)")
    op.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_raw_evidence_run_evidence ON raw_evidence (run_id, evidence_id)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS raw_evidence")
