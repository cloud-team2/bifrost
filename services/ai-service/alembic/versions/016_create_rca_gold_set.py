"""create rca_gold_set table

Revision ID: 016
Revises: 015
Create Date: 2026-06-20
"""
from __future__ import annotations

from alembic import op

revision = "016"
down_revision = "015"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS rca_gold_set (
            entry_id            text        PRIMARY KEY,
            incident_id         text        NOT NULL,
            accepted_root_cause_id text     NOT NULL,
            trigger             text,
            symptom             text,
            contributing_factors jsonb      NOT NULL DEFAULT '[]'::jsonb,
            evidence_ids        jsonb       NOT NULL DEFAULT '[]'::jsonb,
            human_verdict       text,
            labels              jsonb       NOT NULL DEFAULT '[]'::jsonb,
            review_status       text        NOT NULL DEFAULT 'draft',
            reviewed_by         text,
            reviewed_at         timestamptz,
            created_at          timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_gold_set_incident ON rca_gold_set(incident_id)"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_gold_set_root_cause ON rca_gold_set(accepted_root_cause_id)"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_gold_set_review_status ON rca_gold_set(review_status)"
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS rca_gold_set")
