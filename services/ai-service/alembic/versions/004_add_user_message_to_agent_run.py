"""add user_message column to agent_run

Revision ID: 004
Revises: 003
Create Date: 2026-06-07
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "004"
down_revision = "003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("agent_run", sa.Column("user_message", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("agent_run", "user_message")
