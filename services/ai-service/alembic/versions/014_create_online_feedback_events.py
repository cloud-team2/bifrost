"""create online feedback events table

Revision ID: 013
Revises: 012
Create Date: 2026-06-20
"""
from __future__ import annotations

from alembic import op

revision = "014"
down_revision = "013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS online_feedback_events (
            event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            run_id text NOT NULL REFERENCES agent_run(run_id),
            action text NOT NULL CHECK (action IN ('accept', 'modify', 'override')),
            original_root_cause_id text,
            final_root_cause_id text,
            original_confidence double precision,
            final_confidence double precision,
            modified_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
            override_reason text,
            operator_id text,
            metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
            created_at timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_online_feedback_run ON online_feedback_events(run_id)")
    op.execute("CREATE INDEX IF NOT EXISTS idx_online_feedback_created_at ON online_feedback_events(created_at DESC)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS online_feedback_events")
