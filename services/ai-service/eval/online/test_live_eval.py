"""#981 dry-run unit tests for the live RCA eval harness.

클러스터 무접촉. spec 카탈로그 무결성 + 채점 파이프라인(AC@k/Avg@5/ECE)이 fixture 에 대해
정확히 계산되는지 검증한다. legacy live 주입 경로는 release artifact 에서 hard-disabled 이며,
safe-live 는 HTTP 테스트 더블만 사용한다. 실제 cluster 는 절대 건드리지 않는다.
"""
from __future__ import annotations

import pytest

from app.catalogs.root_causes import is_known_root_cause

import eval.online.live_eval as le
from eval.online.live_eval import (
    FaultObservation,
    build_dry_run_observations,
    observations_to_cases,
    run_dry_run,
    run_live,
    run_safe_live,
    score_observations,
)
from eval.online.live_fault_specs import (
    FAULT_SPECS,
    list_fault_specs,
)
from eval.online.safe_live_fault_specs import SAFE_FAULT_SPECS


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


def test_no_legacy_auto_faults_present() -> None:
    assert list_fault_specs("auto") == ()


def test_legacy_fault_catalog_has_no_executable_destructive_steps() -> None:
    forbidden = ("kubectl", "--replicas", "scale deploy", " exec ")
    for spec in FAULT_SPECS:
        for step in (*spec.inject_steps, *spec.recover_steps):
            lowered = step.lower()
            assert not any(token in lowered for token in forbidden), spec.fault_id


def test_safe_faults_present_and_catalog_backed() -> None:
    assert {s.fault_id for s in SAFE_FAULT_SPECS} == {
        "auth", "schema", "lag", "sink-fail", "no-fault",
    }
    for spec in SAFE_FAULT_SPECS:
        for root_cause_id in spec.expected_root_cause_ids:
            assert is_known_root_cause(root_cause_id)


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


# ── live hard-disable / safe-live guard(클러스터 무접촉 보장) ─────────────────
def test_live_requires_confirm() -> None:
    with pytest.raises(SystemExit) as exc:
        run_live(None, confirm=False)
    assert "disabled" in str(exc.value)


def test_live_is_disabled_even_with_confirm() -> None:
    with pytest.raises(SystemExit) as exc:
        run_live(["sink_db_down"], confirm=True)
    assert "safe-live" in str(exc.value)


def test_safe_live_requires_confirm() -> None:
    with pytest.raises(SystemExit):
        run_safe_live(["auth"], confirm=False, safe_project_id="e2e-rca-test")


class _FakeResponse:
    def __init__(self, payload: dict, status_code: int = 200):
        self._payload = payload
        self.status_code = status_code

    def json(self) -> dict:
        return self._payload

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class _FakeSafeClient:
    calls: list[tuple[str, str, dict | None]] = []

    def __init__(self, *args, **kwargs):
        _ = (args, kwargs)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def post(self, url: str, **kwargs):
        self.calls.append(("POST", url, kwargs.get("json")))
        if url.endswith("/api/v1/auth/login"):
            return _FakeResponse({
                "accessToken": "token",
                "workspaceId": "00000000-0000-0000-0000-000000000001",
            })
        if url.endswith("/safe-injection/connectors"):
            return _FakeResponse({
                "ok": True,
                "result": {
                    "run_id": kwargs["json"]["runId"],
                    "fault": kwargs["json"]["fault"],
                    "expected_root_cause_id": "SINK_AUTH_EXPIRED",
                    "pipeline_id": "00000000-0000-0000-0000-000000000002",
                    "datasource_id": "00000000-0000-0000-0000-000000000003",
                    "connector_name": "safeinject-r123-a1",
                    "namespace": "platform-kafka",
                    "labels": {"bifrost.io/safe-inject": "true"},
                    "safe_scope": "safeinject:test-connector-only",
                },
            }, status_code=201)
        if url.endswith("/api/v1/agent/runs"):
            return _FakeResponse({"data": {"run_id": "run_safe_001", "status": "running"}})
        raise AssertionError(f"unexpected POST {url}")

    def get(self, url: str, **kwargs):
        self.calls.append(("GET", url, None))
        if url.endswith("/events/history"):
            return _FakeResponse({"data": {"events": [{"type": "run_completed"}]}})
        if url.endswith("/report"):
            return _FakeResponse({"data": {"body": {"root_cause_candidates": [
                {"root_cause_id": "SINK_AUTH_EXPIRED", "confidence": 0.86},
                {"root_cause_id": "CONNECTOR_TASK_FAILED", "confidence": 0.2},
            ]}}})
        if url.endswith("/reproducibility"):
            return _FakeResponse({"data": {"root_cause_candidates": []}})
        raise AssertionError(f"unexpected GET {url}")

    def delete(self, url: str, **kwargs):
        self.calls.append(("DELETE", url, None))
        if "/safe-injection/runs/" in url:
            return _FakeResponse({
                "ok": True,
                "result": {
                    "run_id": "safe-run",
                    "deleted_k8s_connectors": 1,
                    "deleted_metadata_rows": 3,
                    "residual_count": 0,
                    "residuals": [],
                },
            })
        raise AssertionError(f"unexpected DELETE {url}")


def test_safe_live_injects_scores_and_cleans_without_destructive_helpers(monkeypatch, tmp_path) -> None:
    import httpx

    _FakeSafeClient.calls = []
    monkeypatch.setattr(httpx, "Client", _FakeSafeClient)
    monkeypatch.setattr(le, "REPORTS_DIR", tmp_path)

    report = run_safe_live(
        ["auth"],
        confirm=True,
        ops_base_url="http://ops",
        safe_project_id="e2e-rca-test",
        agent_base_url="http://agent",
        email="ta@bifrost.io",
        password="pw",
        run_id="safe-run",
        poll_timeout_s=1,
        poll_interval_s=0,
    )

    assert report["mode"] == "safe-live"
    assert report["AC@1"] == 1.0
    assert report["cleanup"][0]["residual_count"] == 0
    assert any(
        call[0] == "POST" and call[1].endswith("/safe-injection/connectors")
        for call in _FakeSafeClient.calls
    )
    assert any(
        call[0] == "DELETE" and "/safe-injection/runs/safe-run" in call[1]
        for call in _FakeSafeClient.calls
    )


# ── consistency: 채점이 결정적 ───────────────────────────────────────────────
def test_scoring_is_deterministic() -> None:
    a = score_observations(build_dry_run_observations())
    b = score_observations(build_dry_run_observations())
    for k in ("AC@1", "AC@3", "AC@5", "Avg@5", "ECE"):
        assert a[k] == b[k]
