"""#982 backfill_gold_set 순수 변환 로직 회귀 테스트(DB 불필요).

predicted root cause 만 채우고 accepted_root_cause_id 는 절대 채우지 않는지(정답 날조 금지),
멱등성(이미 적재된 incident_id 건너뛰기)이 지켜지는지 검증한다.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

from app.schemas.gold_set import ReviewStatus

# scripts/ 는 패키지가 아니므로 파일 경로로 직접 로드한다.
_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "backfill_gold_set.py"
_spec = importlib.util.spec_from_file_location("backfill_gold_set", _SCRIPT)
backfill = importlib.util.module_from_spec(_spec)
assert _spec and _spec.loader
_spec.loader.exec_module(backfill)


def _report(incident_id, root_cause_id, *, evidence=None):
    return {
        "incident_id": incident_id,
        "run_id": f"run_{incident_id}",
        "root_cause_id": root_cause_id,
        "confidence": 0.8,
        "body": {"evidence": [{"evidence_id": e} for e in (evidence or [])]},
        "created_at": None,
    }


def test_build_gold_entry_never_sets_accepted_root_cause() -> None:
    entry = backfill.build_gold_entry(_report("inc_1", "CONSUMER_LAG_SPIKE", evidence=["ev_1", "ev_2"]))
    assert entry is not None
    # 정답은 절대 채우지 않는다(검수 전).
    assert entry.accepted_root_cause_id is None
    assert entry.human_verdict is None
    # 예측은 prediction 필드와 라벨에만 저장한다.
    assert entry.predicted_root_cause_id == "CONSUMER_LAG_SPIKE"
    assert entry.review_status == ReviewStatus.UNREVIEWED
    assert entry.evidence_ids == ["ev_1", "ev_2"]
    assert len(entry.labels) == 1
    assert entry.labels[0].value == "CONSUMER_LAG_SPIKE"
    assert "not ground truth" in (entry.labels[0].notes or "")


def test_build_gold_entry_skips_when_no_prediction() -> None:
    assert backfill.build_gold_entry(_report("inc_1", None)) is None
    assert backfill.build_gold_entry(_report(None, "X")) is None


def test_plan_backfill_skips_existing_incidents() -> None:
    reports = [
        _report("inc_1", "RC_A"),
        _report("inc_2", "RC_B"),
        _report("inc_3", None),   # 예측 없음 → 건너뜀
    ]
    entries, stats = backfill.plan_backfill(reports, existing_incident_ids={"inc_1"})

    assert stats["reports"] == 3
    assert stats["skipped_existing"] == 1      # inc_1
    assert stats["skipped_no_prediction"] == 1  # inc_3
    assert stats["to_insert"] == 1             # inc_2
    assert [e.incident_id for e in entries] == ["inc_2"]
    assert all(e.accepted_root_cause_id is None for e in entries)


def test_plan_backfill_is_idempotent_within_batch() -> None:
    reports = [_report("inc_dup", "RC_A"), _report("inc_dup", "RC_A")]
    entries, stats = backfill.plan_backfill(reports, existing_incident_ids=set())
    assert stats["to_insert"] == 1
    assert len(entries) == 1


def test_plan_backfill_empty() -> None:
    entries, stats = backfill.plan_backfill([], existing_incident_ids=set())
    assert entries == []
    assert stats["to_insert"] == 0
    assert stats["reports"] == 0
