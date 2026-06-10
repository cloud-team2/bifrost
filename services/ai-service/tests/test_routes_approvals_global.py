"""routes_approvals — 글로벌 list + 단일 상세 회귀 (issue #394).

docs/api/fastapi.md §10 의 미구현 endpoint 2개:
- GET /api/v1/approvals
- GET /api/v1/approvals/{approval_id}
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api import routes_approvals
from app.main import app
from app.persistence.approval_link_repository import (
    ApprovalLink,
    InMemoryApprovalLinkRepository,
)


client = TestClient(app)


def _now() -> datetime:
    return datetime(2026, 6, 10, 0, 0, tzinfo=UTC)


def _link(
    approval_id: str,
    run_id: str,
    *,
    status: str = "pending",
    action_id: str = "act_001",
    created_at: datetime | None = None,
) -> ApprovalLink:
    return ApprovalLink(
        approval_id=approval_id,
        run_id=run_id,
        action_id=action_id,
        params_hash="deadbeefcafef00d",
        status=status,
        created_at=created_at or _now(),
    )


def _install_repo(
    monkeypatch: pytest.MonkeyPatch, links: list[ApprovalLink]
) -> InMemoryApprovalLinkRepository:
    repo = InMemoryApprovalLinkRepository()
    for link in links:
        repo._store[link.approval_id] = link
    monkeypatch.setattr(routes_approvals, "get_approval_repo", lambda: repo)
    return repo


def test_list_approvals_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    _install_repo(monkeypatch, [])

    res = client.get("/api/v1/approvals")

    assert res.status_code == 200
    body = res.json()
    assert body["ok"] is True
    assert body["data"]["approvals"] == []


def test_list_approvals_status_filter(monkeypatch: pytest.MonkeyPatch) -> None:
    _install_repo(monkeypatch, [
        _link("appr_p", "run_001", status="pending", created_at=_now()),
        _link(
            "appr_a",
            "run_002",
            status="approved",
            created_at=_now() + timedelta(seconds=1),
        ),
        _link(
            "appr_r",
            "run_003",
            status="rejected",
            created_at=_now() + timedelta(seconds=2),
        ),
    ])

    res = client.get("/api/v1/approvals?status=approved")

    body = res.json()
    approvals = body["data"]["approvals"]
    assert [a["approval_id"] for a in approvals] == ["appr_a"]
    assert approvals[0]["status"] == "approved"
    assert approvals[0]["run_id"] == "run_002"


def test_list_approvals_orders_by_created_desc(monkeypatch: pytest.MonkeyPatch) -> None:
    _install_repo(monkeypatch, [
        _link("appr_old", "run_001", created_at=_now()),
        _link("appr_new", "run_002", created_at=_now() + timedelta(seconds=10)),
        _link("appr_mid", "run_003", created_at=_now() + timedelta(seconds=5)),
    ])

    res = client.get("/api/v1/approvals")

    approvals = res.json()["data"]["approvals"]
    assert [a["approval_id"] for a in approvals] == ["appr_new", "appr_mid", "appr_old"]


def test_list_approvals_project_id_filter(monkeypatch: pytest.MonkeyPatch) -> None:
    _install_repo(monkeypatch, [
        _link("appr_x", "run_001"),
        _link("appr_y", "run_002"),
        _link("appr_z", "run_003"),
    ])

    class FakeRunRepo:
        async def list(
            self,
            project_id: str | None = None,
            status: str | None = None,
            limit: int = 20,
        ):
            assert project_id == "proj_alpha"
            return [
                SimpleNamespace(run_id="run_001"),
                SimpleNamespace(run_id="run_003"),
            ]

    from app.persistence import run_repository

    monkeypatch.setattr(run_repository, "get_run_repo", lambda: FakeRunRepo())

    res = client.get("/api/v1/approvals?project_id=proj_alpha")

    approvals = res.json()["data"]["approvals"]
    assert {a["approval_id"] for a in approvals} == {"appr_x", "appr_z"}


def test_get_approval_returns_summary(monkeypatch: pytest.MonkeyPatch) -> None:
    _install_repo(monkeypatch, [
        _link("appr_001", "run_042", status="pending"),
    ])

    res = client.get("/api/v1/approvals/appr_001")

    assert res.status_code == 200
    body = res.json()
    assert body["ok"] is True
    data = body["data"]
    assert data["approval_id"] == "appr_001"
    assert data["run_id"] == "run_042"
    assert data["action_id"] == "act_001"
    assert data["status"] == "pending"
    assert data["params_hash"] == "deadbeefcafef00d"


def test_get_approval_not_found_returns_envelope_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _install_repo(monkeypatch, [])

    res = client.get("/api/v1/approvals/unknown_id")

    assert res.status_code == 200
    body = res.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "APPROVAL_NOT_FOUND"
    assert "unknown_id" in body["error"]["message"]
