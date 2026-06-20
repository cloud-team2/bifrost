"""#884 threshold governance(임계값 레지스트리) — 회귀 테스트.

초기 임계값 로드, 값 조회, 업데이트·롤백·이력, API 엔드포인트를 검증한다.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api import routes_thresholds
from app.core.threshold_registry import (
    InMemoryThresholdRegistry,
    ThresholdEntry,
    _INITIAL_THRESHOLDS,
    get_threshold_registry,
)
from app.main import app

client = TestClient(app)


# ── 초기 임계값 검증 ─────────────────────────────────────────────────────────
def test_initial_thresholds_loaded() -> None:
    registry = InMemoryThresholdRegistry()
    assert len(registry.list()) == len(_INITIAL_THRESHOLDS)


def test_known_thresholds_exist() -> None:
    registry = InMemoryThresholdRegistry()
    assert registry.get("min_confident_root_cause") is not None
    assert registry.get("llm_tie_margin") is not None
    assert registry.get("default_confidence_cap") is not None
    assert registry.get("min_confidence_for_action") is not None
    assert registry.get("error_rate_warning") is not None
    assert registry.get("lag_warning") is not None


def test_get_value_returns_float() -> None:
    registry = InMemoryThresholdRegistry()
    assert registry.get_value("min_confident_root_cause") == 0.60
    assert registry.get_value("llm_tie_margin") == 0.10


def test_get_value_default() -> None:
    registry = InMemoryThresholdRegistry()
    assert registry.get_value("nonexistent", default=99.0) == 99.0


def test_get_value_missing_raises() -> None:
    registry = InMemoryThresholdRegistry()
    with pytest.raises(KeyError):
        registry.get_value("nonexistent")


def test_list_by_category() -> None:
    registry = InMemoryThresholdRegistry()
    rca = registry.list(category="rca")
    alerting = registry.list(category="alerting")
    assert all(e.category == "rca" for e in rca)
    assert all(e.category == "alerting" for e in alerting)
    assert len(rca) + len(alerting) == len(registry.list())


# ── 업데이트·롤백·이력 ───────────────────────────────────────────────────────
def test_update_changes_value_and_version() -> None:
    registry = InMemoryThresholdRegistry()
    updated = registry.update(
        "min_confident_root_cause",
        value=0.65,
        basis="ECE 분석 결과 과신 구간 보정",
        owner="ai-service/eval",
        dataset_version="gold_set_v1",
    )
    assert updated is not None
    assert updated.value == 0.65
    assert updated.version == 2
    assert updated.rollback_value == 0.60
    assert updated.dataset_version == "gold_set_v1"


def test_rollback_restores_previous_value() -> None:
    registry = InMemoryThresholdRegistry()
    registry.update(
        "llm_tie_margin",
        value=0.15,
        basis="test",
        owner="test",
    )
    rolled_back = registry.rollback("llm_tie_margin")
    assert rolled_back is not None
    assert rolled_back.value == 0.10
    assert rolled_back.rollback_value == 0.15


def test_rollback_without_previous_returns_none() -> None:
    registry = InMemoryThresholdRegistry()
    result = registry.rollback("min_confident_root_cause")
    assert result is None


def test_rollback_nonexistent_returns_none() -> None:
    registry = InMemoryThresholdRegistry()
    assert registry.rollback("nonexistent") is None


def test_history_records_all_versions() -> None:
    registry = InMemoryThresholdRegistry()
    registry.update("default_confidence_cap", value=0.75, basis="v2", owner="test")
    registry.update("default_confidence_cap", value=0.85, basis="v3", owner="test")
    history = registry.history("default_confidence_cap")
    assert len(history) == 3
    assert history[0].value == 0.79
    assert history[1].value == 0.75
    assert history[2].value == 0.85


def test_update_nonexistent_returns_none() -> None:
    registry = InMemoryThresholdRegistry()
    assert registry.update("nope", 1.0, "test", "test") is None


# ── ThresholdEntry 검증 ──────────────────────────────────────────────────────
def test_threshold_entry_has_required_fields() -> None:
    registry = InMemoryThresholdRegistry()
    entry = registry.get("min_confident_root_cause")
    assert entry is not None
    assert entry.threshold_name == "min_confident_root_cause"
    assert isinstance(entry.value, float)
    assert isinstance(entry.version, int)
    assert entry.basis
    assert entry.owner
    assert entry.last_calibrated_at is not None


# ── API 엔드포인트 ───────────────────────────────────────────────────────────
def test_list_thresholds_api() -> None:
    resp = client.get("/api/v1/admin/thresholds")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total"] >= len(_INITIAL_THRESHOLDS)
    assert len(data["thresholds"]) == data["total"]


def test_list_thresholds_by_category() -> None:
    resp = client.get("/api/v1/admin/thresholds?category=alerting")
    assert resp.status_code == 200
    for t in resp.json()["data"]["thresholds"]:
        assert t["category"] == "alerting"


def test_get_threshold_api() -> None:
    resp = client.get("/api/v1/admin/thresholds/min_confident_root_cause")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["value"] == 0.60


def test_get_threshold_not_found() -> None:
    resp = client.get("/api/v1/admin/thresholds/nonexistent")
    assert resp.status_code == 200
    assert resp.json()["ok"] is False


def test_update_threshold_api() -> None:
    resp = client.put("/api/v1/admin/thresholds/error_rate_warning", json={
        "value": 1.0,
        "basis": "API 테스트 보정",
        "owner": "test",
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["value"] == 1.0
    assert data["rollback_value"] == 0.5

    client.put("/api/v1/admin/thresholds/error_rate_warning", json={
        "value": 0.5,
        "basis": "rollback to original",
        "owner": "test",
    })


def test_rollback_threshold_api() -> None:
    client.put("/api/v1/admin/thresholds/lag_warning", json={
        "value": 10000.0,
        "basis": "test update",
        "owner": "test",
    })
    resp = client.post("/api/v1/admin/thresholds/lag_warning/rollback")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["value"] == 5000.0


def test_threshold_history_api() -> None:
    resp = client.get("/api/v1/admin/thresholds/min_confident_root_cause/history")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["threshold_name"] == "min_confident_root_cause"
    assert len(data["history"]) >= 1
