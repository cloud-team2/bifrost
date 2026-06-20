"""#888 AC@k/Avg@k 평가 리포트 — 회귀 테스트.

정확도 메트릭 계산, 계층별 breakdown, 약점 계층 식별, API 엔드포인트를 검증한다.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api import routes_gold_set
from app.evaluation.metrics import (
    ACAtKResult,
    AvgAtKResult,
    EvalCase,
    EvalReport,
    accuracy_at_k,
    avg_at_k,
    build_eval_report,
)
from app.evaluation.offline_eval import (
    build_eval_cases_from_gold_set,
    report_to_dict,
    run_offline_eval,
)
from app.main import app
from app.persistence.gold_set_repository import InMemoryGoldSetRepository
from app.schemas.gold_set import GoldSetEntry, ReviewStatus

client = TestClient(app)


# ── 메트릭 단위 테스트 ───────────────────────────────────────────────────────
def _cases() -> list[EvalCase]:
    return [
        EvalCase(
            entry_id="e1",
            accepted_root_cause_id="CONSUMER_LAG_SPIKE",
            predicted_ranking=["CONSUMER_LAG_SPIKE", "BROKER_RESOURCE_PRESSURE", "TOPIC_INGRESS_SPIKE"],
            predicted_confidences=[0.85, 0.60, 0.30],
        ),
        EvalCase(
            entry_id="e2",
            accepted_root_cause_id="SCHEMA_MISMATCH",
            predicted_ranking=["CONNECTOR_TASK_FAILED", "SCHEMA_MISMATCH", "PIPELINE_CONFIG_INVALID"],
            predicted_confidences=[0.70, 0.65, 0.20],
        ),
        EvalCase(
            entry_id="e3",
            accepted_root_cause_id="SOURCE_AUTH_EXPIRED",
            predicted_ranking=["POD_OOM_KILLED", "NODE_PRESSURE"],
            predicted_confidences=[0.90, 0.40],
        ),
    ]


def test_accuracy_at_1() -> None:
    result = accuracy_at_k(_cases(), 1)
    assert result.hit_count == 1
    assert result.total == 3
    assert abs(result.accuracy - 1 / 3) < 1e-6


def test_accuracy_at_3() -> None:
    result = accuracy_at_k(_cases(), 3)
    assert result.hit_count == 2
    assert result.total == 3


def test_accuracy_at_5() -> None:
    result = accuracy_at_k(_cases(), 5)
    assert result.hit_count == 2


def test_avg_at_k_score() -> None:
    result = avg_at_k(_cases(), 5)
    expected = (1.0 + 0.5 + 0.0) / 3
    assert abs(result.score - expected) < 1e-6


def test_perfect_ranking() -> None:
    perfect = [
        EvalCase(
            entry_id="p1",
            accepted_root_cause_id="A",
            predicted_ranking=["A", "B", "C"],
        ),
        EvalCase(
            entry_id="p2",
            accepted_root_cause_id="B",
            predicted_ranking=["B", "A", "C"],
        ),
    ]
    ac1 = accuracy_at_k(perfect, 1)
    assert ac1.accuracy == 1.0
    avg5 = avg_at_k(perfect, 5)
    assert avg5.score == 1.0


def test_empty_predictions() -> None:
    empty = [
        EvalCase(
            entry_id="e_empty",
            accepted_root_cause_id="X",
            predicted_ranking=[],
        ),
    ]
    assert accuracy_at_k(empty, 1).accuracy == 0.0
    assert avg_at_k(empty, 5).score == 0.0


def test_no_cases() -> None:
    assert accuracy_at_k([], 1).accuracy == 0.0
    assert avg_at_k([], 5).score == 0.0


# ── 리포트 생성 ──────────────────────────────────────────────────────────────
def test_build_eval_report_with_layer_map() -> None:
    layer_map = {
        "CONSUMER_LAG_SPIKE": "kafka",
        "SCHEMA_MISMATCH": "pipeline",
        "SOURCE_AUTH_EXPIRED": "source",
    }
    report = build_eval_report(_cases(), layer_map)
    assert report.total_cases == 3
    assert len(report.layer_breakdown) == 3
    kafka = next(lb for lb in report.layer_breakdown if lb.layer == "kafka")
    assert kafka.total == 1
    assert kafka.ac_at_1 == 1.0


def test_weak_layer_detection() -> None:
    weak_cases = [
        EvalCase(
            entry_id="w1",
            accepted_root_cause_id="SOURCE_AUTH_EXPIRED",
            predicted_ranking=["POD_OOM_KILLED"],
        ),
        EvalCase(
            entry_id="w2",
            accepted_root_cause_id="SOURCE_NETWORK_REACHABILITY",
            predicted_ranking=["POD_OOM_KILLED"],
        ),
    ]
    layer_map = {
        "SOURCE_AUTH_EXPIRED": "source",
        "SOURCE_NETWORK_REACHABILITY": "source",
    }
    report = build_eval_report(weak_cases, layer_map)
    assert "source" in report.weak_layers


def test_confidence_threshold_recommendation() -> None:
    cases = [
        EvalCase(
            entry_id="c1",
            accepted_root_cause_id="A",
            predicted_ranking=["A"],
            predicted_confidences=[0.90],
        ),
        EvalCase(
            entry_id="c2",
            accepted_root_cause_id="B",
            predicted_ranking=["C"],
            predicted_confidences=[0.80],
        ),
    ]
    report = build_eval_report(cases)
    assert report.confidence_threshold_recommendation is not None
    assert 0.80 <= report.confidence_threshold_recommendation <= 0.90


# ── offline eval 통합 ────────────────────────────────────────────────────────
def test_build_eval_cases_from_gold_set() -> None:
    entries = [
        GoldSetEntry(
            entry_id="gs_eval_1",
            incident_id="inc_1",
            accepted_root_cause_id="CONSUMER_LAG_SPIKE",
            review_status=ReviewStatus.REVIEWED,
        ),
        GoldSetEntry(
            entry_id="gs_eval_2",
            incident_id="inc_2",
            accepted_root_cause_id="SCHEMA_MISMATCH",
            review_status=ReviewStatus.DRAFT,
        ),
    ]
    predictions = {
        "inc_1": [
            {"root_cause_id": "CONSUMER_LAG_SPIKE", "confidence": 0.85},
            {"root_cause_id": "BROKER_RESOURCE_PRESSURE", "confidence": 0.40},
        ],
    }
    cases = build_eval_cases_from_gold_set(entries, predictions)
    assert len(cases) == 1
    assert cases[0].accepted_root_cause_id == "CONSUMER_LAG_SPIKE"
    assert cases[0].predicted_ranking == ["CONSUMER_LAG_SPIKE", "BROKER_RESOURCE_PRESSURE"]


def test_run_offline_eval_end_to_end() -> None:
    entries = [
        GoldSetEntry(
            entry_id="gs_e2e_1",
            incident_id="inc_e2e_1",
            accepted_root_cause_id="CONNECTOR_TASK_FAILED",
            review_status=ReviewStatus.REVIEWED,
        ),
        GoldSetEntry(
            entry_id="gs_e2e_2",
            incident_id="inc_e2e_2",
            accepted_root_cause_id="POD_OOM_KILLED",
            review_status=ReviewStatus.REVIEWED,
        ),
    ]
    predictions = {
        "inc_e2e_1": [
            {"root_cause_id": "CONNECTOR_TASK_FAILED", "confidence": 0.88},
        ],
        "inc_e2e_2": [
            {"root_cause_id": "NODE_PRESSURE", "confidence": 0.75},
            {"root_cause_id": "POD_OOM_KILLED", "confidence": 0.60},
        ],
    }
    report = run_offline_eval(entries, predictions)
    assert report.total_cases == 2
    assert report.ac_at_1.hit_count == 1
    assert report.ac_at_3.hit_count == 2


def test_report_to_dict_has_required_fields() -> None:
    entries = [
        GoldSetEntry(
            entry_id="gs_dict",
            incident_id="inc_dict",
            accepted_root_cause_id="CONSUMER_LAG_SPIKE",
            review_status=ReviewStatus.REVIEWED,
        ),
    ]
    report = run_offline_eval(entries, {})
    d = report_to_dict(report)
    assert "ac_at_1" in d
    assert "ac_at_3" in d
    assert "ac_at_5" in d
    assert "avg_at_5" in d
    assert "layer_breakdown" in d
    assert "weak_layers" in d
    assert "generated_at" in d


# ── API 엔드포인트 ───────────────────────────────────────────────────────────
def test_eval_report_api_no_entries(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/eval/accuracy-report", json={"predictions": {}})
    assert resp.status_code == 200
    assert resp.json()["ok"] is False


def test_eval_report_api_with_entries(monkeypatch: pytest.MonkeyPatch) -> None:
    import asyncio
    repo = InMemoryGoldSetRepository()

    async def _seed():
        for i in range(3):
            await repo.create(GoldSetEntry(
                entry_id=f"gs_api_{i}",
                incident_id=f"inc_api_{i}",
                accepted_root_cause_id="CONSUMER_LAG_SPIKE",
                review_status=ReviewStatus.REVIEWED,
            ))

    asyncio.run(_seed())
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    predictions = {
        "inc_api_0": [{"root_cause_id": "CONSUMER_LAG_SPIKE", "confidence": 0.9}],
        "inc_api_1": [{"root_cause_id": "SCHEMA_MISMATCH", "confidence": 0.7}],
        "inc_api_2": [
            {"root_cause_id": "POD_OOM_KILLED", "confidence": 0.8},
            {"root_cause_id": "CONSUMER_LAG_SPIKE", "confidence": 0.5},
        ],
    }
    resp = client.post("/api/v1/agent/eval/accuracy-report", json={"predictions": predictions})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total_cases"] == 3
    assert data["ac_at_1"] == pytest.approx(1 / 3, abs=0.01)
    assert data["ac_at_3"] == pytest.approx(2 / 3, abs=0.01)
    assert "layer_breakdown" in data
