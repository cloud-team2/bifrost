"""create KEDB records table

Revision ID: 017
Revises: 016
Create Date: 2026-06-20
"""
from __future__ import annotations

from alembic import op

revision = "017"
down_revision = "016"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS kedb_records (
            root_cause_id text PRIMARY KEY,
            owner text NOT NULL,
            known_symptoms jsonb NOT NULL DEFAULT '[]'::jsonb,
            verified_fixes jsonb NOT NULL DEFAULT '[]'::jsonb,
            rollback_procedure text,
            recurrence_count int NOT NULL DEFAULT 0 CHECK (recurrence_count >= 0),
            last_seen date,
            incident_links jsonb NOT NULL DEFAULT '[]'::jsonb,
            updated_at timestamptz NOT NULL DEFAULT now()
        )
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS kedb_records")
