"""index agent_run.incident_id for cross-turn State reuse

Revision ID: 010
Revises: 009
Create Date: 2026-06-11

#479: action_execution/approval_decision turn이 새 run_id로 들어와도 같은
incident_id의 직전 run이 남긴 조치 후보·policy 결정 State를 복원하려면
agent_run을 incident_id로 최신순 조회해야 한다. 그 조회 경로를 인덱싱한다.
"""
from __future__ import annotations

from alembic import op

revision = "010"
down_revision = "009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_agent_run_incident_created
        ON agent_run (incident_id, created_at DESC)
        WHERE incident_id IS NOT NULL
        """
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_agent_run_incident_created")
