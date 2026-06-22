"""#981 unit tests for NL→tool-routing eval scoring.

네트워크 무접촉. 라벨 세트 무결성 + 채점 로직(routing@1 / routing@hit / wasted_steps) +
trace 추출(events/history 파싱) + live --confirm 가드를 검증한다.
"""
from __future__ import annotations

import pytest

from app.tools.registry import default_tool_definitions

import eval.online.nl_tool_routing as nl
from eval.online.nl_tool_routing import (
    ROUTING_CASES,
    RoutingObservation,
    build_dry_run_observations,
    extract_tool_trace,
    run_dry_run,
    run_live,
    score_observations,
    score_one,
)


# ── 라벨 세트 무결성 ──────────────────────────────────────────────────────────
def test_cases_have_unique_ids() -> None:
    ids = [c.case_id for c in ROUTING_CASES]
    assert len(ids) == len(set(ids))


def test_at_least_twelve_cases() -> None:
    assert len(ROUTING_CASES) >= 12


def test_all_expected_and_acceptable_tools_are_real_catalog_tools() -> None:
    catalog = set(default_tool_definitions().keys())
    for case in ROUTING_CASES:
        assert case.expected_tool in catalog, f"{case.case_id}: {case.expected_tool} 미존재"
        for alt in case.acceptable_tools:
            assert alt in catalog, f"{case.case_id}: acceptable {alt} 미존재"


def test_cases_span_many_tools() -> None:
    # expected_tool 다양성으로 카탈로그를 가로지르는지 확인.
    distinct = {c.expected_tool for c in ROUTING_CASES}
    assert len(distinct) >= 10


def test_korean_and_english_mix() -> None:
    has_ko = any(any("가" <= ch <= "힣" for ch in c.query) for c in ROUTING_CASES)
    has_en = any(c.query.isascii() for c in ROUTING_CASES)
    assert has_ko and has_en


# ── 채점 로직(순수 함수) ──────────────────────────────────────────────────────
def _obs(trace, *, expected="search_logs", accepted=(), error=None, latency=None):
    return RoutingObservation(
        case_id="t", query="q", expected_tool=expected, accepted_tools=tuple(accepted),
        tool_trace=list(trace), error=error, latency_ms=latency,
    )


def test_first_tool_correct_is_routing_at_1() -> None:
    r = score_one(_obs(["search_logs", "get_metrics"]))
    assert r["routing@1"] is True
    assert r["routing@hit"] is True
    assert r["wasted_steps"] == 0


def test_correct_tool_later_is_hit_but_not_at_1() -> None:
    r = score_one(_obs(["list_connectors", "search_logs"]))
    assert r["routing@1"] is False
    assert r["routing@hit"] is True
    assert r["wasted_steps"] == 1  # 정답 앞에 1칸 낭비.


def test_acceptable_alternate_counts_as_hit() -> None:
    r = score_one(_obs(["get_consumer_lag"], expected="get_metrics", accepted=("get_consumer_lag",)))
    assert r["routing@1"] is True
    assert r["routing@hit"] is True


def test_wrong_tool_is_miss() -> None:
    r = score_one(_obs(["get_metrics", "get_alerts"]))
    assert r["routing@1"] is False
    assert r["routing@hit"] is False
    # 정답 미호출 → wasted = 전체 호출 수.
    assert r["wasted_steps"] == 2


def test_empty_trace_is_miss_with_zero_calls() -> None:
    r = score_one(_obs([]))
    assert r["routing@1"] is False
    assert r["routing@hit"] is False
    assert r["wasted_steps"] == 0
    assert r["n_tool_calls"] == 0


def test_aggregate_excludes_errored_rows_from_denominator() -> None:
    obs = [
        _obs(["search_logs"]),                       # hit @1
        _obs(["get_metrics"], error="boom"),         # 에러 → 분모 제외
    ]
    rep = score_observations(obs, mode="dry-run")
    assert rep["total_cases"] == 2
    assert rep["scored_cases"] == 1
    assert rep["errored_cases"] == 1
    assert rep["routing@1"] == 1.0  # scored 1건 중 1건 hit → 1.0(에러 row 가 분모를 안 깎음).


def test_mean_latency_computed_when_present() -> None:
    obs = [_obs(["search_logs"], latency=100.0), _obs(["search_logs"], latency=300.0)]
    rep = score_observations(obs, mode="dry-run")
    assert rep["mean_latency_ms"] == 200.0


# ── trace 추출(events/history 파싱) ──────────────────────────────────────────
def test_extract_tool_trace_orders_by_timestamp() -> None:
    events = [
        {"type": "tool_call_started", "timestamp": "2026-06-22T00:00:02Z", "payload": {"tool": "get_metrics"}},
        {"type": "run_started", "timestamp": "2026-06-22T00:00:00Z", "payload": {}},
        {"type": "tool_call_started", "timestamp": "2026-06-22T00:00:01Z", "payload": {"tool": "search_logs"}},
        {"type": "tool_call_completed", "timestamp": "2026-06-22T00:00:03Z", "payload": {"tool": "search_logs"}},
    ]
    assert extract_tool_trace(events) == ["search_logs", "get_metrics"]


def test_extract_tool_trace_ignores_non_tool_events() -> None:
    events = [
        {"type": "run_started", "timestamp": "t0", "payload": {}},
        {"type": "run_completed", "timestamp": "t9", "payload": {}},
    ]
    assert extract_tool_trace(events) == []


# ── dry-run 파이프라인 ────────────────────────────────────────────────────────
def test_dry_run_metrics_are_meaningful() -> None:
    rep = run_dry_run(write=False)
    # fixture 에 미스/늦은 정답이 섞여 있어 routing@1 < routing@hit < 1, wasted > 0.
    assert 0.0 < rep["routing@1"] < rep["routing@hit"] <= 1.0
    assert rep["mean_wasted_steps"] > 0.0
    assert rep["total_cases"] == len(ROUTING_CASES)


def test_dry_run_is_deterministic() -> None:
    a = score_observations(build_dry_run_observations(), mode="dry-run")
    b = score_observations(build_dry_run_observations(), mode="dry-run")
    for k in ("routing@1", "routing@hit", "mean_wasted_steps"):
        assert a[k] == b[k]


def test_dry_run_writes_reports(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(nl, "REPORTS_DIR", tmp_path)
    rep = run_dry_run(write=True)
    assert "_report_paths" in rep
    json_p = tmp_path / rep["_report_paths"]["json"].split("/")[-1]
    md_p = tmp_path / rep["_report_paths"]["md"].split("/")[-1]
    assert json_p.exists() and md_p.exists()
    assert "routing@1" in md_p.read_text(encoding="utf-8")


# ── live 가드(네트워크 무접촉 보장) ──────────────────────────────────────────
def test_live_requires_confirm() -> None:
    with pytest.raises(SystemExit):
        run_live(None, confirm=False)
