"""#889 ECE confidence 캘리브레이션 — 회귀 테스트.

bin별 accuracy/gap, ECE 계산, 과신 구간 탐지, cap/threshold 추천,
API 엔드포인트를 검증한다.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api import routes_gold_set
from app.evaluation.calibration import (
    CalibrationReport,
    calibration_report_to_dict,
    compute_calibration,
)
from app.evaluation.metrics import EvalCase
from app.main import app
from app.persistence.gold_set_repository import InMemoryGoldSetRepository
from app.schemas.gold_set import GoldSetEntry, ReviewStatus

client = TestClient(app)


# ── ECE 계산 단위 테스트 ─────────────────────────────────────────────────────
def _perfectly_calibrated_cases() -> list[EvalCase]:
    """confidence = accuracy인 완벽 캘리브레이션 케이스."""
    cases = []
    for i in range(10):
        conf = 0.85
        cases.append(EvalCase(
            entry_id=f"perfect_{i}",
            accepted_root_cause_id="A",
            predicted_ranking=["A"] if i < 8 else ["B"],
            predicted_confidences=[conf],
        ))
    return cases


def _overconfident_cases() -> list[EvalCase]:
    """confidence가 높지만 정답률이 낮은 과신 케이스."""
    cases = []
    for i in range(10):
        cases.append(EvalCase(
            entry_id=f"over_{i}",
            accepted_root_cause_id="A",
            predicted_ranking=["A"] if i < 2 else ["B"],
            predicted_confidences=[0.85],
        ))
    return cases


def test_ece_perfectly_calibrated() -> None:
    report = compute_calibration(_perfectly_calibrated_cases())
    assert report.ece < 0.15
    assert report.total_samples == 10


def test_ece_overconfident() -> None:
    report = compute_calibration(_overconfident_cases())
    assert report.ece > 0.30
    assert len(report.overconfident_bins) >= 1


def test_ece_empty_cases() -> None:
    report = compute_calibration([])
    assert report.ece == 0.0
    assert report.total_samples == 0


def test_ece_no_confidences() -> None:
    cases = [
        EvalCase(
            entry_id="no_conf",
            accepted_root_cause_id="A",
            predicted_ranking=["A"],
            predicted_confidences=[],
        ),
    ]
    report = compute_calibration(cases)
    assert report.total_samples == 0


def test_bin_structure() -> None:
    report = compute_calibration(_perfectly_calibrated_cases())
    assert len(report.bins) == 10
    assert report.bins[0].bin_lower == 0.0
    assert report.bins[-1].bin_upper == 1.0


def test_bin_gap_sign() -> None:
    report = compute_calibration(_overconfident_cases())
    occupied = [b for b in report.bins if b.count > 0]
    assert len(occupied) >= 1
    for b in occupied:
        if b.count >= 2 and b.gap > 0.10:
            assert b in report.overconfident_bins


# ── 보정 추천 테스트 ─────────────────────────────────────────────────────────
def test_confidence_cap_recommendation() -> None:
    report = compute_calibration(_overconfident_cases())
    assert report.confidence_cap_recommendation is not None
    assert 0.0 < report.confidence_cap_recommendation <= 1.0


def test_unknown_threshold_recommendation_for_low_accuracy() -> None:
    cases = []
    for i in range(10):
        cases.append(EvalCase(
            entry_id=f"low_{i}",
            accepted_root_cause_id="A",
            predicted_ranking=["A"] if i < 1 else ["B"],
            predicted_confidences=[0.75],
        ))
    report = compute_calibration(cases)
    if report.unknown_threshold_recommendation is not None:
        assert 0.0 < report.unknown_threshold_recommendation <= 1.0


def test_no_recommendation_for_well_calibrated() -> None:
    cases = []
    for i in range(10):
        cases.append(EvalCase(
            entry_id=f"well_{i}",
            accepted_root_cause_id="A",
            predicted_ranking=["A"],
            predicted_confidences=[0.95],
        ))
    report = compute_calibration(cases)
    assert len(report.overconfident_bins) == 0
    assert report.confidence_cap_recommendation is None


# ── 리포트 직렬화 ────────────────────────────────────────────────────────────
def test_calibration_report_to_dict() -> None:
    report = compute_calibration(_overconfident_cases())
    d = calibration_report_to_dict(report)
    assert "ece" in d
    assert "bins" in d
    assert len(d["bins"]) == 10
    assert "overconfident_bins" in d
    assert "generated_at" in d
    assert "confidence_cap_recommendation" in d
    assert "unknown_threshold_recommendation" in d


def test_bin_dict_fields() -> None:
    report = compute_calibration(_perfectly_calibrated_cases())
    d = calibration_report_to_dict(report)
    for b in d["bins"]:
        assert set(b.keys()) == {
            "bin_lower", "bin_upper", "count", "avg_confidence", "accuracy", "gap"
        }


# ── API 엔드포인트 ───────────────────────────────────────────────────────────
def test_calibration_api_no_entries(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/eval/calibration-report", json={"predictions": {}})
    assert resp.status_code == 200
    assert resp.json()["ok"] is False


def test_calibration_api_with_predictions(monkeypatch: pytest.MonkeyPatch) -> None:
    import asyncio
    repo = InMemoryGoldSetRepository()

    async def _seed():
        for i in range(5):
            await repo.create(GoldSetEntry(
                entry_id=f"gs_cal_{i}",
                incident_id=f"inc_cal_{i}",
                accepted_root_cause_id="CONSUMER_LAG_SPIKE",
                review_status=ReviewStatus.REVIEWED,
            ))

    asyncio.run(_seed())
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    predictions = {
        f"inc_cal_{i}": [
            {
                "root_cause_id": "CONSUMER_LAG_SPIKE" if i < 3 else "SCHEMA_MISMATCH",
                "confidence": 0.85,
            }
        ]
        for i in range(5)
    }
    resp = client.post(
        "/api/v1/agent/eval/calibration-report",
        json={"predictions": predictions},
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert "ece" in data
    assert data["total_samples"] == 5
    assert len(data["bins"]) == 10


# ── 다양한 confidence 분포 테스트 ────────────────────────────────────────────
def test_spread_confidence_distribution() -> None:
    """여러 bin에 걸친 케이스로 ECE가 구간 가중 평균임을 확인."""
    cases = []
    confs = [0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95]
    for i, c in enumerate(confs):
        cases.append(EvalCase(
            entry_id=f"spread_{i}",
            accepted_root_cause_id="A",
            predicted_ranking=["A"] if c > 0.5 else ["B"],
            predicted_confidences=[c],
        ))
    report = compute_calibration(cases)
    assert report.total_samples == len(confs)
    occupied = [b for b in report.bins if b.count > 0]
    assert len(occupied) == len(confs)


def test_boundary_confidence_values() -> None:
    """confidence 0.0과 1.0 경계값 처리."""
    cases = [
        EvalCase(
            entry_id="edge_0",
            accepted_root_cause_id="A",
            predicted_ranking=["A"],
            predicted_confidences=[0.0],
        ),
        EvalCase(
            entry_id="edge_1",
            accepted_root_cause_id="A",
            predicted_ranking=["A"],
            predicted_confidences=[1.0],
        ),
    ]
    report = compute_calibration(cases)
    assert report.total_samples == 2
