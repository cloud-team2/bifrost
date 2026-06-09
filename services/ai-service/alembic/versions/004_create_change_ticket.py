"""create change_ticket table

Revision ID: 004
Revises: 003
Create Date: 2026-06-09

Ref: FastAPI routes_change real persistence for issue #251
"""
from __future__ import annotations

revision = "004"
down_revision = "003"
branch_labels = None
depends_on = None

from alembic import op


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS change_ticket (
            id            uuid        PRIMARY KEY,
            run_id        text        NOT NULL REFERENCES agent_run(run_id),
            action_id     text        NOT NULL,
            ticket_id     text        NOT NULL,
            window        text,
            rollback_plan text,
            status        text        NOT NULL DEFAULT 'submitted',
            created_at    timestamptz NOT NULL DEFAULT now(),
            updated_at    timestamptz NOT NULL DEFAULT now(),
            CONSTRAINT change_ticket_status_check CHECK (
                status IN (
                    'submitted',
                    'verified',
                    'CHANGE_TICKET_REQUIRED',
                    'CHANGE_WINDOW_REQUIRED',
                    'ROLLBACK_PLAN_REQUIRED'
                )
            ),
            UNIQUE (run_id, action_id)
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_change_ticket_run_created ON change_ticket (run_id, created_at ASC)")
    op.execute("CREATE INDEX IF NOT EXISTS idx_change_ticket_ticket_id ON change_ticket (ticket_id)")


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_change_ticket_ticket_id")
    op.execute("DROP INDEX IF EXISTS idx_change_ticket_run_created")
    op.execute("DROP TABLE IF EXISTS change_ticket")
