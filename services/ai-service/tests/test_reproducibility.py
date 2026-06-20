"""#885 run 단위 재현성 스키마 — 회귀 테스트.

모델 별칭이 날짜 스냅샷으로 핀고정되는지, manifest 가 필수 필드를 모두 담는지,
재현성 조회 API 가 당시 manifest + RCA 후보 랭킹을 재구성하는지 검증한다.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api import routes_agent
from app.core.reproducibility import build_reproducibility_manifest
from app.llm.model_router import (
    agent_model_snapshot_map,
    model_snapshot_for_agent,
    snapshot_model_id,
)
from app.main import app
from app.persistence.run_repository import InMemoryRunRepository

client = TestClient(app)


# ── 모델 별칭 → 날짜 스냅샷 핀고정 ──────────────────────────────────────────────
def test_model_alias_is_pinned_to_dated_snapshot() -> None:
    assert snapshot_model_id("gpt-4o") == "gpt-4o-2024-08-06"
    assert snapshot_model_id("gpt-4o-mini") == "gpt-4o-mini-2024-07-18"
    # 분석 tier(rca)는 별칭이 아니라 스냅샷 ID 로 기록된다.
    assert model_snapshot_for_agent("rca") == "gpt-4o-2024-08-06"
    tier_map = agent_model_snapshot_map()
    assert tier_map["router"] == "gpt-4o-mini-2024-07-18"
    assert "-" in tier_map["rca"]  # 날짜 스냅샷 형식


# ── manifest 가 §5.2 최소 필드를 모두 담는다 ──────────────────────────────────
def test_manifest_has_all_required_fields() -> None:
    m = build_reproducibility_manifest()
    assert m.model_id == "gpt-4o-2024-08-06"
    assert m.prompt_hash and m.prompt_version
    assert m.evidence_matrix_version.startswith("0.1.0+")
    assert m.runbook_version.startswith("0.1.0+")
    assert m.corpus_manifest_hash and m.corpus_manifest_hash != "unknown"
    assert m.code_commit_sha
    assert isinstance(m.temperature, float)


def test_manifest_is_deterministic() -> None:
    # 같은 코드/설정이면 hash 가 동일해야 재현성이 의미를 가진다.
    assert build_reproducibility_manifest().prompt_hash == build_reproducibility_manifest().prompt_hash


# ── in-memory repo 저장/조회 ──────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_in_memory_repo_persists_reproducibility() -> None:
    repo = InMemoryRunRepository()
    await repo.create("run_x", "incident_analysis")
    manifest = build_reproducibility_manifest().model_dump(mode="json")
    await repo.save_reproducibility("run_x", manifest)
    got = await repo.get_reproducibility("run_x")
    assert got is not None
    assert got["model_id"] == "gpt-4o-2024-08-06"


# ── 재현성 조회 API: manifest + 후보 랭킹 재구성 ──────────────────────────────
def test_reproducibility_api_reconstructs_run(monkeypatch: pytest.MonkeyPatch) -> None:
    from types import SimpleNamespace

    run_id = "run_repro_api"
    repo = InMemoryRunRepository()

    class _StatePatch(SimpleNamespace):
        pass

    class _FakeStateRepo:
        async def get_patches(self, rid: str):
            return [
                _StatePatch(
                    namespace="analysis",
                    path="/analysis/root_cause_candidates",
                    patch={"root_cause_candidates": [
                        {"root_cause_id": "CONSUMER_LAG_SPIKE", "confidence": 0.84},
                        {"root_cause_id": "SCHEMA_MISMATCH", "confidence": 0.41},
                    ]},
                ),
            ]

    async def _seed() -> None:
        await repo.create(run_id, "incident_analysis", user_message="주문 파이프라인 장애")
        await repo.save_reproducibility(
            run_id, build_reproducibility_manifest().model_dump(mode="json")
        )

    import asyncio

    asyncio.run(_seed())
    monkeypatch.setattr(routes_agent, "get_run_repo", lambda: repo)
    monkeypatch.setattr(routes_agent, "get_state_repo", lambda: _FakeStateRepo())

    resp = client.get(f"/api/v1/agent/runs/{run_id}/reproducibility")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["run_id"] == run_id
    assert data["user_message"] == "주문 파이프라인 장애"
    assert data["reproducibility"]["model_id"] == "gpt-4o-2024-08-06"
    # 당시 RCA 후보 랭킹이 그대로 재구성된다.
    assert [c["root_cause_id"] for c in data["root_cause_candidates"]] == [
        "CONSUMER_LAG_SPIKE",
        "SCHEMA_MISMATCH",
    ]


def test_reproducibility_api_404_for_unknown_run(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(routes_agent, "get_run_repo", lambda: InMemoryRunRepository())
    resp = client.get("/api/v1/agent/runs/nope/reproducibility")
    assert resp.status_code == 200
    assert resp.json()["ok"] is False
