"""#888 AC@k / Avg@k 평가 메트릭 — RCA 후보 랭킹 정확도 측정.

AC@k (Accuracy at k): 상위 k개 후보 안에 정답(accepted_root_cause_id)이 포함된 비율.
Avg@k: 정답 순위의 역수(1/rank) 평균 — 순위가 높을수록 높은 점수.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence


@dataclass(frozen=True)
class EvalCase:
    entry_id: str
    accepted_root_cause_id: str
    predicted_ranking: list[str]
    predicted_confidences: list[float] = field(default_factory=list)


@dataclass
class ACAtKResult:
    k: int
    hit_count: int
    total: int

    @property
    def accuracy(self) -> float:
        return self.hit_count / self.total if self.total > 0 else 0.0


@dataclass
class AvgAtKResult:
    k: int
    reciprocal_rank_sum: float
    total: int

    @property
    def score(self) -> float:
        return self.reciprocal_rank_sum / self.total if self.total > 0 else 0.0


@dataclass
class LayerBreakdown:
    layer: str
    total: int
    ac_at_1: float
    ac_at_3: float
    ac_at_5: float
    avg_at_5: float


@dataclass
class EvalReport:
    total_cases: int
    ac_at_1: ACAtKResult
    ac_at_3: ACAtKResult
    ac_at_5: ACAtKResult
    avg_at_5: AvgAtKResult
    layer_breakdown: list[LayerBreakdown]
    weak_layers: list[str]
    confidence_threshold_recommendation: float | None = None


def accuracy_at_k(cases: Sequence[EvalCase], k: int) -> ACAtKResult:
    hits = 0
    for case in cases:
        top_k = case.predicted_ranking[:k]
        if case.accepted_root_cause_id in top_k:
            hits += 1
    return ACAtKResult(k=k, hit_count=hits, total=len(cases))


def avg_at_k(cases: Sequence[EvalCase], k: int) -> AvgAtKResult:
    rr_sum = 0.0
    for case in cases:
        top_k = case.predicted_ranking[:k]
        try:
            rank = top_k.index(case.accepted_root_cause_id) + 1
            rr_sum += 1.0 / rank
        except ValueError:
            pass
    return AvgAtKResult(k=k, reciprocal_rank_sum=rr_sum, total=len(cases))


def _find_rank(case: EvalCase) -> int | None:
    try:
        return case.predicted_ranking.index(case.accepted_root_cause_id) + 1
    except ValueError:
        return None


def build_eval_report(
    cases: Sequence[EvalCase],
    layer_map: dict[str, str] | None = None,
) -> EvalReport:
    ac1 = accuracy_at_k(cases, 1)
    ac3 = accuracy_at_k(cases, 3)
    ac5 = accuracy_at_k(cases, 5)
    avg5 = avg_at_k(cases, 5)

    layer_groups: dict[str, list[EvalCase]] = {}
    if layer_map:
        for case in cases:
            layer = layer_map.get(case.accepted_root_cause_id, "unknown")
            layer_groups.setdefault(layer, []).append(case)

    breakdowns: list[LayerBreakdown] = []
    weak: list[str] = []
    for layer, group in sorted(layer_groups.items()):
        l_ac1 = accuracy_at_k(group, 1).accuracy
        l_ac3 = accuracy_at_k(group, 3).accuracy
        l_ac5 = accuracy_at_k(group, 5).accuracy
        l_avg5 = avg_at_k(group, 5).score
        breakdowns.append(LayerBreakdown(
            layer=layer,
            total=len(group),
            ac_at_1=l_ac1,
            ac_at_3=l_ac3,
            ac_at_5=l_ac5,
            avg_at_5=l_avg5,
        ))
        if l_ac3 < 0.7:
            weak.append(layer)

    threshold_rec = None
    if cases:
        correct_confs = []
        wrong_confs = []
        for case in cases:
            if case.predicted_confidences and case.predicted_ranking:
                top_conf = case.predicted_confidences[0]
                if case.predicted_ranking[0] == case.accepted_root_cause_id:
                    correct_confs.append(top_conf)
                else:
                    wrong_confs.append(top_conf)
        if wrong_confs and correct_confs:
            avg_wrong = sum(wrong_confs) / len(wrong_confs)
            avg_correct = sum(correct_confs) / len(correct_confs)
            threshold_rec = (avg_wrong + avg_correct) / 2

    return EvalReport(
        total_cases=len(cases),
        ac_at_1=ac1,
        ac_at_3=ac3,
        ac_at_5=ac5,
        avg_at_5=avg5,
        layer_breakdown=breakdowns,
        weak_layers=weak,
        confidence_threshold_recommendation=threshold_rec,
    )
