#!/usr/bin/env python3
"""#982 RCA gold set backfill — 과거 인시던트 RCA 결과를 gold set 에 미검수 항목으로 적재한다.

agentdb 의 report_snapshot(인시던트별 RCA 결과)을 읽어, 각 인시던트의 최신 RCA 결과를
rca_gold_set 에 적재한다. 핵심 원칙(정답 날조 금지):

  - accepted_root_cause_id 는 NULL 로 둔다(운영자 검수 전엔 정답이 없음).
  - RCA 가 예측한 root cause 는 predicted_root_cause_id 에 "예측"으로만 저장한다.
  - review_status='unreviewed', human_verdict=NULL 로 적재한다.
  - 운영자가 검수(promote)하기 전까지 평가(AC@k/ECE)에는 사용되지 않는다.

멱등성: 이미 gold set 에 적재된 incident_id 는 건너뛴다.
안전성: 기본은 --dry-run(쓰기 없음). 실제 쓰기는 --commit 일 때만 수행한다.

사용:
    cd services/ai-service
    .venv/bin/python scripts/backfill_gold_set.py --dry-run     # 미리보기(기본)
    .venv/bin/python scripts/backfill_gold_set.py --commit      # 실제 적재
"""
from __future__ import annotations

import argparse
import asyncio
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import settings
from app.core.db import close_pool, get_pool, init_pool
from app.persistence.gold_set_repository import get_gold_set_repo
from app.schemas.gold_set import (
    GoldSetEntry,
    GoldSetLabel,
    LabelCategory,
    ReviewStatus,
)


def _entry_id() -> str:
    return f"gs_bf_{uuid.uuid4().hex[:10]}"


def _extract_evidence_ids(body: dict[str, Any]) -> list[str]:
    """report_snapshot.body 의 evidence 목록에서 evidence_id 를 추출한다."""
    ids: list[str] = []
    for item in body.get("evidence", []) or []:
        if isinstance(item, dict):
            eid = item.get("evidence_id") or item.get("id")
            if eid:
                ids.append(str(eid))
    return ids


def build_gold_entry(report: dict[str, Any]) -> GoldSetEntry | None:
    """단일 인시던트의 최신 RCA 결과(report_snapshot 행)를 미검수 gold set 항목으로 변환한다.

    예측 root cause 가 없으면(RCA 가 UNKNOWN 등) None 을 반환해 건너뛴다.
    정답(accepted_root_cause_id)은 절대 채우지 않는다 — 예측은 prediction 필드/라벨에만 저장.
    """
    incident_id = report.get("incident_id")
    predicted = report.get("root_cause_id")
    if not incident_id or not predicted:
        return None

    body = report.get("body") or {}
    if isinstance(body, str):
        import json as _json

        body = _json.loads(body)

    evidence_ids = _extract_evidence_ids(body)

    # 예측을 정답이 아닌 "예측"으로 명시 기록한 라벨(검수 시 채택/거부/수정 대상).
    labels = [
        GoldSetLabel(
            label_id=f"lbl_{uuid.uuid4().hex[:8]}",
            category=LabelCategory.ROOT_CAUSE,
            value=str(predicted),
            evidence_ids=evidence_ids,
            notes="RCA prediction (unreviewed; not ground truth)",
        )
    ]

    return GoldSetEntry(
        entry_id=_entry_id(),
        incident_id=str(incident_id),
        accepted_root_cause_id=None,          # 정답 미확정 — 운영자 검수 전
        predicted_root_cause_id=str(predicted),
        trigger=None,                          # report_snapshot 에 trigger 컬럼 없음 — 검수 시 라벨링
        symptom=None,                          # report_snapshot 에 symptom 컬럼 없음 — 검수 시 라벨링
        evidence_ids=evidence_ids,
        human_verdict=None,                    # 미검수
        labels=labels,
        review_status=ReviewStatus.UNREVIEWED,
        reviewed_by=None,
        reviewed_at=None,
        created_at=datetime.now(timezone.utc),
    )


async def _fetch_latest_reports_by_incident() -> list[dict[str, Any]]:
    """agentdb report_snapshot 에서 인시던트별 최신 RCA 결과 1건씩 읽는다(read-only)."""
    pool = get_pool()
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT DISTINCT ON (incident_id)
                   incident_id, run_id, root_cause_id, confidence, body, created_at
            FROM report_snapshot
            WHERE incident_id IS NOT NULL
            ORDER BY incident_id, created_at DESC
            """
        )
    return [dict(r) for r in rows]


def plan_backfill(
    reports: list[dict[str, Any]],
    existing_incident_ids: set[str],
) -> tuple[list[GoldSetEntry], dict[str, int]]:
    """순수 변환 — DB 없이 단위 테스트 가능. 적재할 항목과 카운터를 반환한다."""
    to_insert: list[GoldSetEntry] = []
    stats = {
        "reports": len(reports),
        "skipped_existing": 0,
        "skipped_no_prediction": 0,
        "to_insert": 0,
    }
    seen: set[str] = set()
    for report in reports:
        incident_id = report.get("incident_id")
        if not incident_id:
            stats["skipped_no_prediction"] += 1
            continue
        incident_id = str(incident_id)
        if incident_id in existing_incident_ids or incident_id in seen:
            stats["skipped_existing"] += 1
            continue
        entry = build_gold_entry(report)
        if entry is None:
            stats["skipped_no_prediction"] += 1
            continue
        seen.add(incident_id)
        to_insert.append(entry)
    stats["to_insert"] = len(to_insert)
    return to_insert, stats


def _print_plan(entries: list[GoldSetEntry], stats: dict[str, int], *, dry_run: bool) -> None:
    mode = "DRY-RUN (no writes)" if dry_run else "COMMIT"
    print(f"=== RCA gold set backfill [{mode}] ===")
    print(f"report_snapshot incidents read : {stats['reports']}")
    print(f"skipped (already in gold set)  : {stats['skipped_existing']}")
    print(f"skipped (no RCA prediction)    : {stats['skipped_no_prediction']}")
    print(f"to insert (unreviewed)         : {stats['to_insert']}")
    if entries:
        print("\n-- entries that would be inserted --")
        for e in entries:
            print(
                f"  incident={e.incident_id} "
                f"predicted_root_cause={e.predicted_root_cause_id} "
                f"accepted=NULL review_status={e.review_status.value} "
                f"evidence={len(e.evidence_ids)}"
            )


async def _run(dry_run: bool) -> int:
    await init_pool(settings.database_url)
    try:
        repo = get_gold_set_repo()
        existing = await repo.existing_incident_ids()
        reports = await _fetch_latest_reports_by_incident()
        entries, stats = plan_backfill(reports, existing)
        _print_plan(entries, stats, dry_run=dry_run)

        if dry_run:
            print("\n[dry-run] no rows written. Re-run with --commit to persist.")
            return 0

        inserted = 0
        for entry in entries:
            await repo.create(entry)
            inserted += 1
        print(f"\n[commit] inserted {inserted} unreviewed gold set entries.")
        return 0
    finally:
        await close_pool()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Backfill rca_gold_set from historical RCA report_snapshot results "
        "(unreviewed predictions; no fabricated ground truth)."
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--dry-run",
        action="store_true",
        help="Preview what would be inserted without writing (default).",
    )
    group.add_argument(
        "--commit",
        action="store_true",
        help="Actually insert rows into rca_gold_set.",
    )
    args = parser.parse_args(argv)

    # 기본은 dry-run. --commit 이 명시될 때만 실제 쓰기.
    dry_run = not args.commit
    try:
        return asyncio.run(_run(dry_run=dry_run))
    except Exception as exc:  # noqa: BLE001 — surface DB/connectivity errors clearly
        print(f"backfill failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
