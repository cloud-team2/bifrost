"""#889 ECE confidence 캘리브레이션 — 과신 구간 탐지 및 보정 리포트.

Expected Calibration Error (ECE): confidence bin별 avg_confidence와 실제 accuracy의
가중 평균 차이. ECE가 낮을수록 AI의 자신감 표현이 실제 정답률에 가깝다.

bin 구성: [0, 0.1), [0.1, 0.2), ..., [0.9, 1.0]
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

from app.evaluation.metrics import EvalCase


@dataclass
class CalibrationBin:
    bin_lower: float
    bin_upper: float
    count: int = 0
    total_confidence: float = 0.0
    correct_count: int = 0

    @property
    def avg_confidence(self) -> float:
        return self.total_confidence / self.count if self.count > 0 else 0.0

    @property
    def accuracy(self) -> float:
        return self.correct_count / self.count if self.count > 0 else 0.0

    @property
    def gap(self) -> float:
        return self.avg_confidence - self.accuracy


@dataclass
class CalibrationReport:
    bins: list[CalibrationBin]
    ece: float
    total_samples: int
    overconfident_bins: list[CalibrationBin]
    underconfident_bins: list[CalibrationBin]
    confidence_cap_recommendation: float | None = None
    unknown_threshold_recommendation: float | None = None


def _build_bins(n_bins: int = 10) -> list[CalibrationBin]:
    step = 1.0 / n_bins
    return [
        CalibrationBin(bin_lower=round(i * step, 2), bin_upper=round((i + 1) * step, 2))
        for i in range(n_bins)
    ]


def _assign_to_bin(confidence: float, bins: list[CalibrationBin]) -> CalibrationBin | None:
    for b in bins:
        if b.bin_lower <= confidence < b.bin_upper:
            return b
        if b.bin_upper == 1.0 and confidence == 1.0:
            return b
    return None


def compute_calibration(
    cases: Sequence[EvalCase],
    n_bins: int = 10,
) -> CalibrationReport:
    bins = _build_bins(n_bins)
    total = 0

    for case in cases:
        if not case.predicted_ranking or not case.predicted_confidences:
            continue
        top_conf = case.predicted_confidences[0]
        is_correct = case.predicted_ranking[0] == case.accepted_root_cause_id
        b = _assign_to_bin(top_conf, bins)
        if b is None:
            continue
        b.count += 1
        b.total_confidence += top_conf
        if is_correct:
            b.correct_count += 1
        total += 1

    ece = 0.0
    if total > 0:
        for b in bins:
            if b.count > 0:
                ece += (b.count / total) * abs(b.gap)

    overconfident = [b for b in bins if b.count >= 2 and b.gap > 0.10]
    underconfident = [b for b in bins if b.count >= 2 and b.gap < -0.10]

    cap_rec = None
    if overconfident:
        worst = max(overconfident, key=lambda b: b.gap)
        cap_rec = worst.bin_lower

    unknown_rec = None
    if overconfident:
        low_accuracy_bins = [b for b in overconfident if b.accuracy < 0.50]
        if low_accuracy_bins:
            unknown_rec = max(b.bin_upper for b in low_accuracy_bins)

    return CalibrationReport(
        bins=bins,
        ece=ece,
        total_samples=total,
        overconfident_bins=overconfident,
        underconfident_bins=underconfident,
        confidence_cap_recommendation=cap_rec,
        unknown_threshold_recommendation=unknown_rec,
    )


def calibration_report_to_dict(report: CalibrationReport) -> dict:
    from datetime import datetime, timezone

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "ece": round(report.ece, 4),
        "total_samples": report.total_samples,
        "bins": [
            {
                "bin_lower": b.bin_lower,
                "bin_upper": b.bin_upper,
                "count": b.count,
                "avg_confidence": round(b.avg_confidence, 4),
                "accuracy": round(b.accuracy, 4),
                "gap": round(b.gap, 4),
            }
            for b in report.bins
        ],
        "overconfident_bins": [
            {"range": f"[{b.bin_lower}, {b.bin_upper})", "gap": round(b.gap, 4)}
            for b in report.overconfident_bins
        ],
        "underconfident_bins": [
            {"range": f"[{b.bin_lower}, {b.bin_upper})", "gap": round(b.gap, 4)}
            for b in report.underconfident_bins
        ],
        "confidence_cap_recommendation": report.confidence_cap_recommendation,
        "unknown_threshold_recommendation": report.unknown_threshold_recommendation,
    }
