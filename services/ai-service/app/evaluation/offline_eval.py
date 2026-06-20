"""#888 offline evaluation runner — gold set 기반 AC@k/Avg@k 리포트 생성.

gold set의 reviewed 항목을 대상으로 RCA 후보 랭킹과 대조해 정확도를 산출한다.
state_patches에서 각 run의 root_cause_candidates를 추출해 predicted_ranking을 구성한다.
"""
from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime, timezone

from app.catalogs.root_causes import ROOT_CAUSE_INDEX
from app.evaluation.metrics import EvalCase, EvalReport, build_eval_report
from app.schemas.gold_set import GoldSetEntry, ReviewStatus


def _build_layer_map() -> dict[str, str]:
    return {rc_id: rc.layer for rc_id, rc in ROOT_CAUSE_INDEX.items()}


def build_eval_cases_from_gold_set(
    entries: list[GoldSetEntry],
    predictions: dict[str, list[dict]],
) -> list[EvalCase]:
    """gold set entries + run 예측 결과를 EvalCase 리스트로 변환한다.

    predictions: {incident_id: [{"root_cause_id": ..., "confidence": ...}, ...]}
    """
    cases: list[EvalCase] = []
    for entry in entries:
        if entry.review_status != ReviewStatus.REVIEWED:
            continue
        preds = predictions.get(entry.incident_id, [])
        if not preds:
            cases.append(EvalCase(
                entry_id=entry.entry_id,
                accepted_root_cause_id=entry.accepted_root_cause_id,
                predicted_ranking=[],
                predicted_confidences=[],
            ))
            continue

        ranking = [p["root_cause_id"] for p in preds]
        confidences = [p.get("confidence", 0.0) for p in preds]
        cases.append(EvalCase(
            entry_id=entry.entry_id,
            accepted_root_cause_id=entry.accepted_root_cause_id,
            predicted_ranking=ranking,
            predicted_confidences=confidences,
        ))
    return cases


def run_offline_eval(
    entries: list[GoldSetEntry],
    predictions: dict[str, list[dict]],
) -> EvalReport:
    cases = build_eval_cases_from_gold_set(entries, predictions)
    layer_map = _build_layer_map()
    return build_eval_report(cases, layer_map)


def report_to_dict(report: EvalReport) -> dict:
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total_cases": report.total_cases,
        "ac_at_1": report.ac_at_1.accuracy,
        "ac_at_3": report.ac_at_3.accuracy,
        "ac_at_5": report.ac_at_5.accuracy,
        "avg_at_5": report.avg_at_5.score,
        "layer_breakdown": [
            {
                "layer": lb.layer,
                "total": lb.total,
                "ac_at_1": lb.ac_at_1,
                "ac_at_3": lb.ac_at_3,
                "ac_at_5": lb.ac_at_5,
                "avg_at_5": lb.avg_at_5,
            }
            for lb in report.layer_breakdown
        ],
        "weak_layers": report.weak_layers,
        "confidence_threshold_recommendation": report.confidence_threshold_recommendation,
    }
