"""create agent_message table — 에이전트 대화 메모리(thread 모델)

Revision ID: 011
Revises: 010
Create Date: 2026-06-15

#712: 에이전트가 무상태라 같은 인시던트에 대한 후속 질문이 직전 대화를 기억하지 못한다.
대화를 thread_id로 묶어 turn(user/assistant)별로 저장한다. 인시던트 채팅은 thread_id =
incident_id, 그 외 자유 대화는 클라이언트가 부여한 thread_id를 쓴다. run 생성 시 같은 thread의
이전 메시지를 최신순으로 로드해 LLM 컨텍스트에 주입한다.
"""
from __future__ import annotations

from alembic import op

revision = "011"
down_revision = "010"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS agent_message (
            id          uuid        PRIMARY KEY,
            thread_id   text        NOT NULL,
            project_id  uuid,
            role        text        NOT NULL,
            content     text        NOT NULL,
            run_id      text        REFERENCES agent_run(run_id),
            created_at  timestamptz NOT NULL DEFAULT now()
        )
    """)
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_agent_message_thread_created "
        "ON agent_message (thread_id, created_at)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_agent_message_thread_created")
    op.execute("DROP TABLE IF EXISTS agent_message")
