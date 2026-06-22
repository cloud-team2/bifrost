"""gold set: nullable accepted_root_cause_id + predicted_root_cause_id

#982 운영자 검수 전(unreviewed) 항목과 backfill 예측을 정답과 구분해 저장하기 위해
- accepted_root_cause_id 의 NOT NULL 제약을 푼다(검수 전엔 정답이 없음).
- predicted_root_cause_id 컬럼을 추가해 RCA 예측을 정답이 아닌 "예측"으로 명시 저장한다.

Revision ID: 018
Revises: 017
Create Date: 2026-06-22
"""
from __future__ import annotations

from alembic import op

revision = "018"
down_revision = "017"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("ALTER TABLE rca_gold_set ALTER COLUMN accepted_root_cause_id DROP NOT NULL")
    op.execute("ALTER TABLE rca_gold_set ADD COLUMN IF NOT EXISTS predicted_root_cause_id text")
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_gold_set_predicted_root_cause "
        "ON rca_gold_set(predicted_root_cause_id)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_gold_set_predicted_root_cause")
    op.execute("ALTER TABLE rca_gold_set DROP COLUMN IF EXISTS predicted_root_cause_id")
    # NOT NULL 복원은 NULL 데이터가 있으면 실패하므로 정합 데이터에서만 수행한다.
    op.execute(
        "ALTER TABLE rca_gold_set ALTER COLUMN accepted_root_cause_id SET NOT NULL"
    )
