"""create agent run store tables

Revision ID: 001
Revises:
Create Date: 2026-06-05

Tables: agent_run, state_patch, run_event
Ref: docs/design/backend-fastapi/server-design.md §9.2
"""
from __future__ import annotations

revision = "001"
down_revision = None
branch_labels = None
depends_on = None

from alembic import op
import sqlalchemy as sa


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS agent_run (
            run_id                text        PRIMARY KEY,
            project_id            uuid,
            requested_by          text,
            mode                  text        NOT NULL,
            remediation_requested boolean     NOT NULL DEFAULT false,
            incident_id           text,
            status                text        NOT NULL DEFAULT 'running',
            current_agent         text,
            catalog_version       text        NOT NULL DEFAULT '0.1.0',
            created_at            timestamptz NOT NULL DEFAULT now(),
            updated_at            timestamptz NOT NULL DEFAULT now(),
            closed_at             timestamptz
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS state_patch (
            id          bigserial   PRIMARY KEY,
            run_id      text        NOT NULL REFERENCES agent_run(run_id),
            seq         int         NOT NULL,
            namespace   text        NOT NULL,
            author      text        NOT NULL,
            op          text        NOT NULL,
            path        text        NOT NULL,
            patch       jsonb       NOT NULL,
            created_at  timestamptz NOT NULL DEFAULT now(),
            UNIQUE (run_id, seq)
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS run_event (
            event_id    text        PRIMARY KEY,
            run_id      text        NOT NULL REFERENCES agent_run(run_id),
            seq         int         NOT NULL,
            type        text        NOT NULL,
            agent       text,
            message     text        NOT NULL,
            payload     jsonb,
            created_at  timestamptz NOT NULL DEFAULT now(),
            UNIQUE (run_id, seq)
        )
    """)

    op.execute("CREATE INDEX IF NOT EXISTS idx_state_patch_run_seq ON state_patch (run_id, seq)")
    op.execute("CREATE INDEX IF NOT EXISTS idx_run_event_run_seq   ON run_event  (run_id, seq)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS run_event")
    op.execute("DROP TABLE IF EXISTS state_patch")
    op.execute("DROP TABLE IF EXISTS agent_run")
