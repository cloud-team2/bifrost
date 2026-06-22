"""#964/#888 포괄 RCA 정확도 평가 캠페인.

35개 gold set(8계층 root cause별 대표 시나리오)를 *관측 증거*(symptom+trigger+contributing_factors)
만으로 배포된 RCA 로직(run_rca)에 통과시켜 AC@1/AC@3/AC@5/Avg@5/ECE/기권율을 산출한다.
human_verdict(사후 결론)는 증거에서 제외해 정답 누출을 막는다.

실행(floor, 재현):  cd services/ai-service && .venv/bin/python scripts/rca_eval_campaign.py
실행(LLM-on, 라이브 정확도):  RCA_EVAL_USE_LLM=1 python scripts/rca_eval_campaign.py
  └ 실 LLM/임베딩 구성이 있는 곳(배포 pod/Job)에서만 의미. 비파괴(read-only RCA 평가).
    in-cluster 전체 35 라이브 평가 Job 생성법은 docs/test/rca-exhaustive-test-20260622.md §Part C 참고.
"""
from __future__ import annotations

import asyncio
import json
import os

import app.llm.provider as _prov
from app.agents.rca import UNKNOWN_ROOT_CAUSE_ID, run_rca
from app.catalogs.incident_rootcause_map import INCIDENT_ROOT_CAUSE_MAP
from app.catalogs.root_causes import get_root_cause
from app.evaluation.metrics import EvalCase, build_eval_report
from app.evaluation.seed_gold_set import build_seed_entries
from app.schemas.outputs import (
    Classification,
    ClassifierOutput,
    IncidentTypeOutput,
    RetrievalOutput,
)
from app.schemas.state import EvidenceItem, EvidenceType, IncidentScope


# 기본: 카탈로그-only floor(LLM 타이브레이커 비활성, 재현 가능).
# RCA_EVAL_USE_LLM=1 → 실제 LLM provider 사용(타이브레이커 포함 라이브 정확도 측정).
class _DummyLLM:
    async def generate(self, messages, model=None):  # noqa: ANN001
        return ""


if os.getenv("RCA_EVAL_USE_LLM") != "1":
    _prov.get_llm_provider = lambda: _DummyLLM()


# root_cause -> incident_type (분류기가 줄 법한 유형). 1순위 후보로 등장하는 유형을 우선.
def _build_root_to_incident() -> dict[str, str]:
    primary: dict[str, str] = {}
    secondary: dict[str, str] = {}
    for entry in INCIDENT_ROOT_CAUSE_MAP:
        for idx, rc in enumerate(entry.root_cause_ids):
            (primary if idx == 0 else secondary).setdefault(rc, entry.incident_type)
    return {**secondary, **primary}


ROOT_TO_INCIDENT = _build_root_to_incident()


def _classifier(incident_type: str) -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[IncidentTypeOutput(type=incident_type, confidence=0.9, evidence_ids=[])],
        )
    )


def _retrieval(summaries: list[str]) -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id=f"ev-{i}",
                type=EvidenceType.PIPELINE_LOG,
                store_ref=f"evidence://run/ev-{i}",
                summary=s,
            )
            for i, s in enumerate(summaries, start=1)
        ]
    )


def _ece(cases: list[EvalCase], n_bins: int = 10) -> float:
    bins: list[list[tuple[float, bool]]] = [[] for _ in range(n_bins)]
    for c in cases:
        if not c.predicted_confidences:
            continue
        conf = c.predicted_confidences[0]
        correct = bool(c.predicted_ranking[:1] == [c.accepted_root_cause_id])
        b = min(int(conf * n_bins), n_bins - 1)
        bins[b].append((conf, correct))
    total = sum(len(b) for b in bins)
    if not total:
        return 0.0
    ece = 0.0
    for b in bins:
        if not b:
            continue
        acc = sum(1 for _, ok in b if ok) / len(b)
        avg = sum(cf for cf, _ in b) / len(b)
        ece += (len(b) / total) * abs(avg - acc)
    return ece


async def main() -> None:
    entries = build_seed_entries()
    cases: list[EvalCase] = []
    rows: list[dict] = []
    layer_map: dict[str, str] = {}

    for ent in entries:
        rc = ent.accepted_root_cause_id
        layer_map[rc] = (get_root_cause(rc).layer if get_root_cause(rc) else "unknown")
        incident_type = ROOT_TO_INCIDENT.get(rc, "UNKNOWN_NEEDS_MORE_EVIDENCE")
        # 관측 증거만(결론 human_verdict 제외)
        summaries = [s for s in [ent.symptom, ent.trigger, *(ent.contributing_factors or [])] if s]
        result = await run_rca(_classifier(incident_type), _retrieval(summaries))
        ranking = [c.root_cause_id for c in result.root_cause_candidates]
        confs = [c.confidence for c in result.root_cause_candidates]
        cases.append(
            EvalCase(entry_id=ent.entry_id, accepted_root_cause_id=rc,
                     predicted_ranking=ranking, predicted_confidences=confs)
        )
        rows.append({
            "entry": ent.entry_id, "layer": layer_map[rc], "incident_type": incident_type,
            "expected": rc, "top": ranking[0] if ranking else None,
            "top_conf": round(confs[0], 3) if confs else None,
            "hit@1": bool(ranking[:1] == [rc]), "hit@3": rc in ranking[:3], "hit@5": rc in ranking[:5],
        })

    report = build_eval_report(cases, layer_map)
    abstain = sum(1 for c in cases if not c.predicted_ranking or c.predicted_ranking[0] == UNKNOWN_ROOT_CAUSE_ID)
    out = {
        "total_cases": report.total_cases,
        "AC@1": round(report.ac_at_1.accuracy, 4),
        "AC@3": round(report.ac_at_3.accuracy, 4),
        "AC@5": round(report.ac_at_5.accuracy, 4),
        "Avg@5": round(report.avg_at_5.score, 4),
        "ECE": round(_ece(cases), 4),
        "abstain_count": abstain,
        "weak_layers": report.weak_layers,
        "layer_breakdown": [
            {"layer": lb.layer, "n": lb.total, "AC@1": round(lb.ac_at_1, 3),
             "AC@3": round(lb.ac_at_3, 3), "AC@5": round(lb.ac_at_5, 3), "Avg@5": round(lb.avg_at_5, 3)}
            for lb in report.layer_breakdown
        ],
        "rows": rows,
    }
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
