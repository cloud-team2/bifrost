"""rename change_ticket.window to change_window (reserved keyword fix)

Revision ID: 007
Revises: 006

Create Date: 2026-06-10

Ref: #360 — `window` is a PostgreSQL reserved keyword, so using it as an
unquoted column name causes syntax errors. Keep 005 history intact and
normalize with a new revision.
"""
from __future__ import annotations

from alembic import op

revision = "007"
down_revision = "006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # If an existing DB somehow applied 005 with a quoted "window" column,
    # normalize it. Fresh DBs are covered by the idempotent CREATE below.
    op.execute("""
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_name = 'change_ticket' AND column_name = 'window'
            ) THEN
                ALTER TABLE change_ticket RENAME COLUMN "window" TO change_window;
            END IF;
        END $$;
    """)
    op.execute("""
        CREATE TABLE IF NOT EXISTS change_ticket (
            id             uuid        PRIMARY KEY,
            run_id         text        NOT NULL REFERENCES agent_run(run_id),
            action_id      text        NOT NULL,
            ticket_id      text        NOT NULL,
            change_window  text,
            rollback_plan  text,
            status         text        NOT NULL DEFAULT 'submitted',
            created_at     timestamptz NOT NULL DEFAULT now(),
            updated_at     timestamptz NOT NULL DEFAULT now(),
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
    op.execute("""
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_name = 'change_ticket' AND column_name = 'change_window'
            ) THEN
                ALTER TABLE change_ticket RENAME COLUMN change_window TO "window";
            END IF;
        END $$;
    """)
