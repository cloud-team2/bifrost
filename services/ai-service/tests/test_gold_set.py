"""#887 RCA gold set·라벨링 프로토콜 — 회귀 테스트.

gold set CRUD, 라벨링 가이드, 시드 데이터, 검수 워크플로, API 엔드포인트를 검증한다.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api import routes_gold_set
from app.evaluation.seed_gold_set import SEED_ENTRIES, build_seed_entries
from app.main import app
from app.persistence.gold_set_repository import InMemoryGoldSetRepository
from app.schemas.gold_set import (
    LABELING_GUIDE,
    LABELING_RULES,
    GoldSetEntry,
    GoldSetLabel,
    LabelCategory,
    ReviewStatus,
)

client = TestClient(app)


# ── 스키마 검증 ──────────────────────────────────────────────────────────────
def test_gold_set_entry_requires_root_cause_id() -> None:
    with pytest.raises(Exception):
        GoldSetEntry(
            entry_id="gs_x",
            incident_id="inc_x",
            accepted_root_cause_id="",
        )


def test_label_category_enum_values() -> None:
    assert set(LabelCategory) == {
        LabelCategory.TRIGGER,
        LabelCategory.SYMPTOM,
        LabelCategory.ROOT_CAUSE,
        LabelCategory.CONTRIBUTING_FACTOR,
    }


def test_review_status_enum_values() -> None:
    assert set(ReviewStatus) == {
        ReviewStatus.UNREVIEWED,
        ReviewStatus.DRAFT,
        ReviewStatus.REVIEWED,
        ReviewStatus.DISPUTED,
    }


# ── 라벨링 가이드 ────────────────────────────────────────────────────────────
def test_labeling_guide_covers_all_categories() -> None:
    for cat in LabelCategory:
        assert cat.value in LABELING_GUIDE, f"missing guide for {cat.value}"


def test_labeling_rules_not_empty() -> None:
    assert len(LABELING_RULES) >= 5


# ── 시드 데이터 ──────────────────────────────────────────────────────────────
def test_seed_has_at_least_30_entries() -> None:
    assert len(SEED_ENTRIES) >= 30


def test_seed_entries_cover_all_layers() -> None:
    from app.catalogs.root_causes import ROOT_CAUSE_INDEX

    seed_rc_ids = {e["accepted_root_cause_id"] for e in SEED_ENTRIES}
    seed_layers = {
        ROOT_CAUSE_INDEX[rc_id].layer
        for rc_id in seed_rc_ids
        if rc_id in ROOT_CAUSE_INDEX
    }
    expected_layers = {"source", "pipeline", "kafka", "sink", "infra", "change", "data_quality"}
    assert expected_layers.issubset(seed_layers)


def test_seed_build_entries_all_reviewed() -> None:
    entries = build_seed_entries()
    assert all(e.review_status == ReviewStatus.REVIEWED for e in entries)
    assert all(e.reviewed_by == "seed" for e in entries)


def test_seed_entries_have_labels() -> None:
    entries = build_seed_entries()
    for entry in entries:
        root_labels = [l for l in entry.labels if l.category == LabelCategory.ROOT_CAUSE]
        assert len(root_labels) == 1, f"{entry.entry_id} missing root_cause label"


# ── InMemory 저장소 ──────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_in_memory_repo_crud() -> None:
    repo = InMemoryGoldSetRepository()
    entry = GoldSetEntry(
        entry_id="gs_test_001",
        incident_id="inc_test_001",
        accepted_root_cause_id="CONSUMER_LAG_SPIKE",
        trigger="upstream 이벤트 폭증",
        symptom="consumer lag 급증",
    )
    await repo.create(entry)
    assert await repo.get("gs_test_001") is not None
    assert await repo.count() == 1

    entries = await repo.list()
    assert len(entries) == 1

    updated = await repo.update_review(
        "gs_test_001",
        ReviewStatus.REVIEWED,
        reviewed_by="tester",
        human_verdict="확인 완료",
    )
    assert updated is not None
    assert updated.review_status == ReviewStatus.REVIEWED
    assert updated.reviewed_by == "tester"
    assert updated.reviewed_at is not None

    assert await repo.count(review_status=ReviewStatus.REVIEWED) == 1
    assert await repo.count(review_status=ReviewStatus.DRAFT) == 0

    deleted = await repo.delete("gs_test_001")
    assert deleted is True
    assert await repo.get("gs_test_001") is None


@pytest.mark.asyncio
async def test_in_memory_repo_list_filters() -> None:
    repo = InMemoryGoldSetRepository()
    e1 = GoldSetEntry(
        entry_id="gs_f1",
        incident_id="inc_f1",
        accepted_root_cause_id="CONSUMER_LAG_SPIKE",
        review_status=ReviewStatus.REVIEWED,
    )
    e2 = GoldSetEntry(
        entry_id="gs_f2",
        incident_id="inc_f2",
        accepted_root_cause_id="SCHEMA_MISMATCH",
        review_status=ReviewStatus.DRAFT,
    )
    await repo.create(e1)
    await repo.create(e2)

    reviewed = await repo.list(review_status=ReviewStatus.REVIEWED)
    assert len(reviewed) == 1
    assert reviewed[0].entry_id == "gs_f1"

    by_rc = await repo.list(root_cause_id="SCHEMA_MISMATCH")
    assert len(by_rc) == 1
    assert by_rc[0].entry_id == "gs_f2"


# ── inter-review 일관성 체크 ─────────────────────────────────────────────────
def test_inter_review_consistency_seed_entries() -> None:
    """같은 root_cause_id를 가진 시드 항목들의 trigger 키워드가 유사해야 한다."""
    entries = build_seed_entries()
    by_rc: dict[str, list[GoldSetEntry]] = {}
    for e in entries:
        by_rc.setdefault(e.accepted_root_cause_id, []).append(e)
    for rc_id, group in by_rc.items():
        if len(group) < 2:
            continue
        triggers = [e.trigger for e in group if e.trigger]
        assert len(triggers) >= 1, f"{rc_id}: group has no triggers"


# ── API 엔드포인트 ───────────────────────────────────────────────────────────
def test_create_and_get_gold_set_entry(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/gold-set", json={
        "incident_id": "inc_api_001",
        "accepted_root_cause_id": "CONSUMER_LAG_SPIKE",
        "trigger": "이벤트 폭증",
        "symptom": "lag 급증",
        "labels": [
            {"category": "root_cause", "value": "CONSUMER_LAG_SPIKE"},
        ],
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    entry_id = data["entry_id"]
    assert data["accepted_root_cause_id"] == "CONSUMER_LAG_SPIKE"
    assert len(data["labels"]) == 1

    resp2 = client.get(f"/api/v1/agent/gold-set/{entry_id}")
    assert resp2.status_code == 200
    assert resp2.json()["data"]["incident_id"] == "inc_api_001"


def test_review_gold_set_entry(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/gold-set", json={
        "incident_id": "inc_api_002",
        "accepted_root_cause_id": "SCHEMA_MISMATCH",
    })
    entry_id = resp.json()["data"]["entry_id"]

    resp2 = client.patch(f"/api/v1/agent/gold-set/{entry_id}/review", json={
        "review_status": "reviewed",
        "reviewed_by": "reviewer_a",
        "human_verdict": "확인 완료",
    })
    assert resp2.status_code == 200
    assert resp2.json()["data"]["review_status"] == "reviewed"
    assert resp2.json()["data"]["reviewed_by"] == "reviewer_a"


def test_list_gold_set_entries(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    for i in range(3):
        client.post("/api/v1/agent/gold-set", json={
            "incident_id": f"inc_list_{i}",
            "accepted_root_cause_id": "CONSUMER_LAG_SPIKE",
        })

    resp = client.get("/api/v1/agent/gold-set")
    assert resp.status_code == 200
    assert resp.json()["data"]["total"] == 3


def test_delete_gold_set_entry(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/gold-set", json={
        "incident_id": "inc_del",
        "accepted_root_cause_id": "POD_OOM_KILLED",
    })
    entry_id = resp.json()["data"]["entry_id"]

    resp2 = client.delete(f"/api/v1/agent/gold-set/{entry_id}")
    assert resp2.status_code == 200
    assert resp2.json()["data"]["deleted"] is True

    resp3 = client.get(f"/api/v1/agent/gold-set/{entry_id}")
    assert resp3.json()["ok"] is False


def test_gold_set_stats(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.get("/api/v1/agent/gold-set-stats")
    assert resp.status_code == 200
    assert resp.json()["data"]["total"] == 0


def test_labeling_guide_api() -> None:
    resp = client.get("/api/v1/agent/gold-set-labeling-guide")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert "categories" in data
    assert "rules" in data
    assert len(data["rules"]) >= 5


# ── #982 운영자 평결 승격(promote) ───────────────────────────────────────────
def test_promote_accepted_creates_reviewed_entry(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/gold-set/promote", json={
        "incident_id": "inc_promote_001",
        "verdict": "accepted",
        "reviewed_by": "ta@bifrost.io",
        "predicted_root_cause_id": "CONSUMER_LAG_SPIKE",
        "run_id": "run-1",
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["review_status"] == "reviewed"
    assert data["accepted_root_cause_id"] == "CONSUMER_LAG_SPIKE"
    assert data["predicted_root_cause_id"] == "CONSUMER_LAG_SPIKE"
    assert data["human_verdict"] == "accepted"
    assert data["reviewed_by"] == "ta@bifrost.io"


def test_promote_corrected_uses_corrected_root_cause(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/gold-set/promote", json={
        "incident_id": "inc_promote_002",
        "verdict": "corrected",
        "reviewed_by": "ta@bifrost.io",
        "predicted_root_cause_id": "CONSUMER_LAG_SPIKE",
        "corrected_root_cause_id": "SCHEMA_MISMATCH",
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["review_status"] == "reviewed"
    assert data["accepted_root_cause_id"] == "SCHEMA_MISMATCH"
    assert data["predicted_root_cause_id"] == "CONSUMER_LAG_SPIKE"


def test_promote_corrected_requires_corrected_root_cause(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/gold-set/promote", json={
        "incident_id": "inc_promote_003",
        "verdict": "corrected",
        "reviewed_by": "ta@bifrost.io",
    })
    assert resp.status_code == 200
    assert resp.json()["ok"] is False


def test_promote_rejected_is_disputed_without_truth(monkeypatch: pytest.MonkeyPatch) -> None:
    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    resp = client.post("/api/v1/agent/gold-set/promote", json={
        "incident_id": "inc_promote_004",
        "verdict": "rejected",
        "reviewed_by": "ta@bifrost.io",
        "predicted_root_cause_id": "CONSUMER_LAG_SPIKE",
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["review_status"] == "disputed"
    assert data["accepted_root_cause_id"] is None
    assert data["predicted_root_cause_id"] == "CONSUMER_LAG_SPIKE"


def test_promote_upserts_existing_unreviewed_entry(monkeypatch: pytest.MonkeyPatch) -> None:
    """backfill 로 적재된 미검수 항목을 운영자 평결로 in-place 갱신한다(중복 생성 금지)."""
    import asyncio

    repo = InMemoryGoldSetRepository()
    monkeypatch.setattr(routes_gold_set, "get_gold_set_repo", lambda: repo)

    # backfill 로 적재된 unreviewed 예측 항목을 모사한다.
    asyncio.run(
        repo.create(
            GoldSetEntry(
                entry_id="gs_bf_existing",
                incident_id="inc_promote_005",
                accepted_root_cause_id=None,
                predicted_root_cause_id="CONSUMER_LAG_SPIKE",
                review_status=ReviewStatus.UNREVIEWED,
            )
        )
    )

    resp = client.post("/api/v1/agent/gold-set/promote", json={
        "incident_id": "inc_promote_005",
        "verdict": "accepted",
        "reviewed_by": "ta@bifrost.io",
        "predicted_root_cause_id": "CONSUMER_LAG_SPIKE",
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["entry_id"] == "gs_bf_existing"  # 갱신, 신규 생성 아님
    assert data["review_status"] == "reviewed"
    assert data["accepted_root_cause_id"] == "CONSUMER_LAG_SPIKE"
    # 같은 incident 항목이 1개로 유지되어야 한다.
    count = asyncio.run(repo.count())
    assert count == 1
