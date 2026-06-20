"""create run_telemetry table

Revision ID: 015
Revises: 014
Create Date: 2026-06-20
"""
from __future__ import annotations

from alembic import op

revision = "015"
down_revision = "014"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS run_telemetry (
            run_id              text        PRIMARY KEY REFERENCES agent_run(run_id),
            total_latency_ms    float       NOT NULL DEFAULT 0,
            total_stages        int         NOT NULL DEFAULT 0,
            called_agents       jsonb       NOT NULL DEFAULT '[]'::jsonb,
            called_tools        jsonb       NOT NULL DEFAULT '{}'::jsonb,
            total_tool_calls    int         NOT NULL DEFAULT 0,
            total_llm_calls     int         NOT NULL DEFAULT 0,
            total_estimated_tokens int      NOT NULL DEFAULT 0,
            stages              jsonb       NOT NULL DEFAULT '[]'::jsonb,
            handoff_reasons     jsonb       NOT NULL DEFAULT '[]'::jsonb,
            created_at          timestamptz NOT NULL DEFAULT now()
        )
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS run_telemetry")
