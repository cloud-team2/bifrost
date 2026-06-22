"""#981 dry-run unit tests for the live RCA eval harness.

클러스터 무접촉. spec 카탈로그 무결성 + 채점 파이프라인(AC@k/Avg@5/ECE)이 fixture 에 대해
정확히 계산되는지 검증한다. live 주입 경로는 가드(NotImplementedError/SystemExit) 만 확인한다.
"""
from __future__ import annotations

import pytest

from app.catalogs.root_causes import is_known_root_cause

from eval.online.live_eval import (
    FaultObservation,
    build_dry_run_observations,
    observations_to_cases,
    run_dry_run,
    run_live,
    score_observations,
)
from eval.online.live_fault_specs import (
    FAULT_SPECS,
    list_fault_specs,
)


# ── spec 카탈로그 무결성 ──────────────────────────────────────────────────────
def test_specs_cover_all_eight_layers() -> None:
    layers = {s.layer for s in FAULT_SPECS}
    # 카탈로그 8계층 중 unknown 을 제외한 운영 가능한 계층을 모두 커버.
    expected = {"source", "sink", "pipeline", "kafka", "infra", "change", "data_quality"}
    assert expected.issubset(layers), f"missing layers: {expected - layers}"


def test_all_expected_root_causes_are_catalog_ids() -> None:
    for spec in FAULT_SPECS:
        for rc in (*spec.expected_root_cause_ids, *spec.confusion_root_cause_ids):
            assert is_known_root_cause(rc), f"{spec.fault_id}: {rc} not in catalog"


def test_safety_levels_are_valid_and_auto_have_steps() -> None:
    for spec in FAULT_SPECS:
        assert spec.safety in {"auto", "manual", "unsafe"}
        if spec.safety == "auto":
            assert spec.inject_steps and spec.recover_steps, spec.fault_id


def test_auto_faults_present() -> None:
    auto = list_fault_specs("auto")
    assert {s.fault_id for s in auto} == {"sink_db_down", "source_db_down"}


# ── 채점 파이프라인(테스트 더블에 안 기댄 순수 계산) ──────────────────────────
def test_acceptable_set_top1_counts_as_hit() -> None:
    # acceptable set 의 두 번째 id 가 top-1 이어도 hit@1 으로 인정돼야 한다.
    obs = FaultObservation(
        fault_id="source_db_down",
        expected_root_cause_ids=("SOURCE_DB_CONNECTION_TIMEOUT", "SOURCE_NETWORK_REACHABILITY"),
        predicted_ranking=["SOURCE_NETWORK_REACHABILITY", "SOURCE_READ_LATENCY"],
        predicted_confidences=[0.8, 0.3],
    )
    cases = observations_to_cases([obs])
    case = cases[0]
    assert case.predicted_ranking[0] == case.accepted_root_cause_id  # hit@1


def test_score_monotonic_ac_at_k() -> None:
    report = run_dry_run(write=False)
    assert report["AC@1"] <= report["AC@3"] <= report["AC@5"]
    # fixture 에 top1/top3/top5 정답이 섞여 있어 단계별로 증가해야 한다.
    assert report["AC@1"] < report["AC@5"]


def test_score_ece_is_computed_and_nonzero() -> None:
    # fixture 에 과신 오답(broker_resource_pressure: conf 0.9, wrong)이 있어 ECE > 0.
    report = run_dry_run(write=False)
    assert report["ECE"] > 0.0
    assert "calibration" in report
    assert report["calibration"]["total_samples"] == report["total_cases"]


def test_avg_at_5_within_bounds() -> None:
    report = run_dry_run(write=False)
    assert 0.0 <= report["Avg@5"] <= 1.0


def test_layer_breakdown_present() -> None:
    report = run_dry_run(write=False)
    layers = {lb["layer"] for lb in report["layer_breakdown"]}
    assert "sink" in layers and "source" in layers


def test_dry_run_writes_reports(tmp_path, monkeypatch) -> None:
    import eval.online.live_eval as le

    monkeypatch.setattr(le, "REPORTS_DIR", tmp_path)
    report = run_dry_run(write=True)
    assert "_report_paths" in report
    json_p = tmp_path / report["_report_paths"]["json"].split("/")[-1]
    md_p = tmp_path / report["_report_paths"]["md"].split("/")[-1]
    assert json_p.exists() and md_p.exists()
    assert "AC@1" in md_p.read_text(encoding="utf-8")


# ── live 가드(클러스터 무접촉 보장) ──────────────────────────────────────────
def test_live_requires_confirm() -> None:
    with pytest.raises(SystemExit):
        run_live(None, confirm=False)


def test_live_inject_helpers_are_guarded() -> None:
    # confirm 을 줘도 dedup-resolve/poll 이 NotImplementedError 가드로 막혀 실제 주입이 안 일어남.
    with pytest.raises(NotImplementedError):
        run_live(["sink_db_down"], confirm=True)


# ── consistency: 채점이 결정적 ───────────────────────────────────────────────
def test_scoring_is_deterministic() -> None:
    a = score_observations(build_dry_run_observations())
    b = score_observations(build_dry_run_observations())
    for k in ("AC@1", "AC@3", "AC@5", "Avg@5", "ECE"):
        assert a[k] == b[k]
