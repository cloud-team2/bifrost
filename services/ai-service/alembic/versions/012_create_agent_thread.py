"""create agent_thread table — 멀티 채팅방(세션) #821

Revision ID: 012
Revises: 011
Create Date: 2026-06-17

#821: ChatGPT/Claude식 멀티 채팅방. 그동안 thread_id는 agent_message에만 문자열로 존재했는데,
채팅방을 1급 레코드로 승격해 제목·소유자(owner)·생성/수정 시각을 관리한다. agent_message.thread_id가
이 id(text)를 가리킨다. 개인별 스코프(owner) — owner는 호출 측이 부여(project_id와 동일한 신뢰 모델).
인시던트 채팅(thread_id=incident_id)은 별도 레코드 없이 기존대로 동작한다.
"""
from __future__ import annotations

from alembic import op

revision = "012"
down_revision = "011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS agent_thread (
            id          text        PRIMARY KEY,
            project_id  uuid        NOT NULL,
            owner       text        NOT NULL,
            title       text,
            archived    boolean     NOT NULL DEFAULT false,
            created_at  timestamptz NOT NULL DEFAULT now(),
            updated_at  timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_agent_thread_owner_updated "
        "ON agent_thread (project_id, owner, updated_at DESC)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_agent_thread_owner_updated")
    op.execute("DROP TABLE IF EXISTS agent_thread")
