"""Online feedback drift metrics and recalibration triggers (#890)."""
from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass

from app.persistence.online_feedback_repository import OnlineFeedbackEvent

UNKNOWN_ROOT_CAUSE = "UNKNOWN_WITH_EVIDENCE_GAP"


@dataclass(frozen=True, slots=True)
class DriftThresholds:
    confidence_psi: float = 0.20
    unknown_ratio: float = 0.25
    root_cause_concentration: float = 0.50
    override_ratio: float = 0.20
    min_events: int = 5


def build_drift_report(
    events: list[OnlineFeedbackEvent],
    *,
    thresholds: DriftThresholds | None = None,
) -> dict[str, object]:
    cfg = thresholds or DriftThresholds()
    total = len(events)
    if total == 0:
        return {
            "event_count": 0,
            "confidence_psi": 0.0,
            "unknown_ratio": 0.0,
            "top_root_cause": None,
            "top_root_cause_ratio": 0.0,
            "override_ratio": 0.0,
            "drift_signals": [],
            "recalibration_triggered": False,
        }

    confidence_values = [
        value
        for event in events
        for value in [event.final_confidence if event.final_confidence is not None else event.original_confidence]
        if value is not None
    ]
    confidence_psi = _confidence_psi(confidence_values)
    root_counts = Counter(_final_root_cause(event) for event in events if _final_root_cause(event))
    top_root_cause, top_count = root_counts.most_common(1)[0] if root_counts else (None, 0)
    top_ratio = top_count / total if total else 0.0
    unknown_ratio = sum(1 for event in events if _is_unknown(event)) / total
    override_ratio = sum(1 for event in events if event.action == "override") / total

    signals: list[str] = []
    enough = total >= cfg.min_events
    if enough and confidence_psi >= cfg.confidence_psi:
        signals.append("confidence_distribution_drift")
    if enough and unknown_ratio >= cfg.unknown_ratio:
        signals.append("unknown_ratio_spike")
    if enough and top_ratio >= cfg.root_cause_concentration:
        signals.append("root_cause_overprediction")
    if enough and override_ratio >= cfg.override_ratio:
        signals.append("operator_override_increase")

    return {
        "event_count": total,
        "confidence_psi": round(confidence_psi, 4),
        "unknown_ratio": round(unknown_ratio, 4),
        "top_root_cause": top_root_cause,
        "top_root_cause_ratio": round(top_ratio, 4),
        "override_ratio": round(override_ratio, 4),
        "drift_signals": signals,
        "recalibration_triggered": bool(signals),
    }


def _confidence_psi(values: list[float]) -> float:
    if not values:
        return 0.0
    expected = [0.05, 0.10, 0.20, 0.30, 0.35]
    actual_counts = [0, 0, 0, 0, 0]
    for value in values:
        bounded = max(0.0, min(1.0, value))
        index = min(4, int(bounded * 5))
        actual_counts[index] += 1
    actual = [count / len(values) for count in actual_counts]
    return sum(_psi_term(exp, act) for exp, act in zip(expected, actual, strict=True))


def _psi_term(expected: float, actual: float) -> float:
    eps = 1e-6
    exp = max(expected, eps)
    act = max(actual, eps)
    return (act - exp) * math.log(act / exp)


def _final_root_cause(event: OnlineFeedbackEvent) -> str | None:
    return event.final_root_cause_id or event.original_root_cause_id


def _is_unknown(event: OnlineFeedbackEvent) -> bool:
    root_cause = _final_root_cause(event)
    return root_cause == UNKNOWN_ROOT_CAUSE or root_cause == "UNKNOWN"
