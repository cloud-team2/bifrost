"""add change ticket impact analysis and verifier plan

Revision ID: 008
Revises: 007

Create Date: 2026-06-11

Ref: #484 — Change Management Gate must verify impact analysis and verifier plan.
"""
from __future__ import annotations

from alembic import op

revision = "008"
down_revision = "007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE change_ticket
            ADD COLUMN IF NOT EXISTS impact_analysis text,
            ADD COLUMN IF NOT EXISTS verifier_plan text
    """)
    op.execute("ALTER TABLE change_ticket DROP CONSTRAINT IF EXISTS change_ticket_status_check")
    op.execute("""
        ALTER TABLE change_ticket
            ADD CONSTRAINT change_ticket_status_check CHECK (
                status IN (
                    'submitted',
                    'verified',
                    'CHANGE_TICKET_REQUIRED',
                    'CHANGE_WINDOW_REQUIRED',
                    'ROLLBACK_PLAN_REQUIRED',
                    'IMPACT_ANALYSIS_REQUIRED',
                    'VERIFIER_PLAN_REQUIRED'
                )
            )
    """)


def downgrade() -> None:
    op.execute("ALTER TABLE change_ticket DROP CONSTRAINT IF EXISTS change_ticket_status_check")
    op.execute("""
        ALTER TABLE change_ticket
            ADD CONSTRAINT change_ticket_status_check CHECK (
                status IN (
                    'submitted',
                    'verified',
                    'CHANGE_TICKET_REQUIRED',
                    'CHANGE_WINDOW_REQUIRED',
                    'ROLLBACK_PLAN_REQUIRED'
                )
            )
    """)
    op.execute("""
        ALTER TABLE change_ticket
            DROP COLUMN IF EXISTS verifier_plan,
            DROP COLUMN IF EXISTS impact_analysis
    """)
