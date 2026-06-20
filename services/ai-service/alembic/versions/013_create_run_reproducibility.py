"""create run_reproducibility table — run 단위 재현성 핀고정(#885)

Revision ID: 013
Revises: 012
Create Date: 2026-06-20

#885: RCA run 을 나중에 똑같이 재현하려면 당시 모델·프롬프트·카탈로그·코드 버전이
run 마다 고정 저장돼야 한다. agent_run.catalog_version 만으로는 부족해, 모델 스냅샷
ID·프롬프트 해시·evidence matrix/runbook 버전·corpus manifest 해시·평가셋 버전·
코드 commit SHA·temperature 를 run_id 1:1 로 저장한다. (설계문서 §7-4 / §5.2)
"""
from __future__ import annotations

from alembic import op

revision = "013"
down_revision = "012"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS run_reproducibility (
            run_id                 text        PRIMARY KEY REFERENCES agent_run(run_id),
            model_id               text        NOT NULL,
            model_tier_map         jsonb       NOT NULL DEFAULT '{}'::jsonb,
            prompt_version         text        NOT NULL,
            prompt_hash            text        NOT NULL,
            catalog_version        text        NOT NULL,
            evidence_matrix_version text       NOT NULL,
            runbook_version        text        NOT NULL,
            corpus_manifest_hash   text        NOT NULL,
            eval_dataset_version   text        NOT NULL DEFAULT 'none',
            code_commit_sha        text        NOT NULL DEFAULT 'unknown',
            temperature            double precision NOT NULL DEFAULT 0,
            created_at             timestamptz NOT NULL DEFAULT now()
        )
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS run_reproducibility")
