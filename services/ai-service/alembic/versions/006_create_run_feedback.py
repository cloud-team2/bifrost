"""create run_feedback table

Revision ID: 006
Revises: 005
Create Date: 2026-06-09
"""
from __future__ import annotations

from alembic import op

revision = "006"
down_revision = "005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS run_feedback (
            feedback_id   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
            run_id        text        NOT NULL REFERENCES agent_run(run_id),
            rating        int         NOT NULL CHECK (rating BETWEEN 1 AND 5),
            category      text        NOT NULL,
            comment       text,
            submitted_by  text,
            created_at    timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_run_feedback_run ON run_feedback(run_id)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS run_feedback")
