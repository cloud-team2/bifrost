"""create report_snapshot table

Revision ID: 003
Revises: 002
Create Date: 2026-06-09

Ref: docs/design/backend-fastapi/server-design.md §9.2
"""
from __future__ import annotations

revision = "003"
down_revision = "002"
branch_labels = None
depends_on = None

from alembic import op


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS report_snapshot (
            id            uuid        PRIMARY KEY,
            run_id        text        NOT NULL REFERENCES agent_run(run_id),
            incident_id   text,
            root_cause_id text,
            confidence    numeric,
            verified      boolean     NOT NULL DEFAULT false,
            body          jsonb       NOT NULL,
            created_at    timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_report_snapshot_run_created ON report_snapshot (run_id, created_at DESC)")
    op.execute("""
        CREATE INDEX IF NOT EXISTS idx_report_snapshot_incident_created
        ON report_snapshot (incident_id, created_at DESC)
        WHERE incident_id IS NOT NULL
    """)


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_report_snapshot_incident_created")
    op.execute("DROP INDEX IF EXISTS idx_report_snapshot_run_created")
    op.execute("DROP TABLE IF EXISTS report_snapshot")
