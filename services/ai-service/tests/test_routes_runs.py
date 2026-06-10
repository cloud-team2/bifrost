from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any
from uuid import UUID

from fastapi.testclient import TestClient

from app.api import routes_runs
from app.main import app
from app.persistence.state_repository import StatePatchRecord
from app.schemas.events import StreamingEvent, StreamingEventType


client = TestClient(app)


def _now() -> datetime:
    return datetime(2026, 6, 1, 0, 0, tzinfo=UTC)


class FakeRunRecord(SimpleNamespace):
    def model_dump(self, **_: Any) -> dict[str, Any]:
        return dict(self.__dict__)


class FakeRunRepo:
    def __init__(self, runs: list[FakeRunRecord]) -> None:
        self.runs = {run.run_id: run for run in runs}
        self.updates: list[tuple[str, str, str | None]] = []

    async def get(self, run_id: str) -> FakeRunRecord | None:
        return self.runs.get(run_id)

    async def update_status(
        self,
        run_id: str,
        status: str,
        current_agent: str | None = None,
    ) -> None:
        self.updates.append((run_id, status, current_agent))
        if run_id in self.runs:
            self.runs[run_id].status = status
            self.runs[run_id].current_agent = current_agent

    async def list(
        self,
        project_id: str | None = None,
        status: str | None = None,
        limit: int = 20,
    ) -> list[FakeRunRecord]:
        runs = list(self.runs.values())
        if project_id is not None:
            runs = [run for run in runs if run.project_id == project_id]
        if status is not None:
            runs = [run for run in runs if run.status == status]
        runs.sort(key=lambda run: run.created_at, reverse=True)
        return runs[:limit]


class FakeStateRepo:
    def __init__(self, patches: list[StatePatchRecord]) -> None:
        self.patches = patches

    async def get_patches(self, run_id: str, from_seq: int = 0) -> list[StatePatchRecord]:
        return [
            patch for patch in self.patches
            if patch.run_id == run_id and patch.seq > from_seq
        ]


class FakeEventRepo:
    def __init__(self, events: list[StreamingEvent]) -> None:
        self.events = events

    def get_after(self, run_id: str, last_event_id: str | None) -> list[StreamingEvent]:
        assert last_event_id is None
        return [event for event in self.events if event.run_id == run_id]


def _run(
    run_id: str = "run_001",
    *,
    status: str = "running",
    project_id: str = "proj_001",
    created_at: datetime | None = None,
) -> FakeRunRecord:
    return FakeRunRecord(
        run_id=run_id,
        project_id=project_id,
        mode="incident_analysis",
        status=status,
        current_agent=None,
        created_at=created_at or _now(),
    )


def _patch(
    seq: int,
    namespace: str,
    author: str,
    op: str = "append",
    path: str = "/",
    patch: dict[str, Any] | None = None,
    created_at: datetime | None = None,
) -> StatePatchRecord:
    return StatePatchRecord(
        run_id="run_001",
        seq=seq,
        namespace=namespace,
        author=author,
        op=op,
        path=path,
        patch=patch or {},
        created_at=created_at or (_now() + timedelta(seconds=seq)),
    )


def _install(
    monkeypatch,
    *,
    runs: list[FakeRunRecord] | None = None,
    patches: list[StatePatchRecord] | None = None,
    events: list[StreamingEvent] | None = None,
) -> FakeRunRepo:
    run_repo = FakeRunRepo(runs or [_run()])
    monkeypatch.setattr(routes_runs, "get_run_repo", lambda: run_repo)
    monkeypatch.setattr(routes_runs, "get_state_repo", lambda: FakeStateRepo(patches or []))
    monkeypatch.setattr(routes_runs, "get_event_repo", lambda: FakeEventRepo(events or []))
    return run_repo


def test_state_summary_returns_namespace_counts(monkeypatch):
    _install(monkeypatch, patches=[
        _patch(1, "evidence", "Retrieval"),
        _patch(2, "evidence", "Retrieval"),
        _patch(3, "analysis", "RCA", op="replace"),
    ])

    res = client.get("/api/v1/agent/runs/run_001/state/summary")

    assert res.status_code == 200
    data = res.json()["data"]
    assert data["run_id"] == "run_001"
    assert data["mode"] == "incident_analysis"
    assert data["namespaces"]["evidence"]["patch_count"] == 2
    assert data["namespaces"]["analysis"]["last_author"] == "RCA"
    assert data["namespaces"]["analysis"]["last_op"] == "replace"
    assert data["guards"]["step_count"] == 3


def test_timeline_merges_patches_and_events(monkeypatch):
    ts = _now()
    event = StreamingEvent(
        event_id="evt_001",
        run_id="run_001",
        timestamp=ts + timedelta(seconds=2),
        type=StreamingEventType.AGENT_COMPLETED,
        agent="Retrieval",
        message="evidence collected",
    )
    _install(monkeypatch, patches=[
        _patch(1, "evidence", "Retrieval", created_at=ts + timedelta(seconds=1)),
        _patch(2, "analysis", "RCA", created_at=ts + timedelta(seconds=3)),
    ], events=[event])

    res = client.get("/api/v1/agent/runs/run_001/timeline")

    data = res.json()["data"]
    assert [item["type"] for item in data["items"]] == [
        "state_patch",
        "agent_completed",
        "state_patch",
    ]
    assert data["items"][1]["message"] == "evidence collected"
    assert data["next_cursor"] == 2


def test_steps_groups_by_author(monkeypatch):
    _install(monkeypatch, patches=[
        _patch(1, "evidence", "Retrieval"),
        _patch(2, "evidence", "Retrieval"),
        _patch(3, "analysis", "RCA"),
    ])

    res = client.get("/api/v1/agent/runs/run_001/steps")

    steps = res.json()["data"]["steps"]
    assert [step["agent"] for step in steps] == ["Retrieval", "RCA"]
    assert steps[0]["status"] == "completed"


def test_actions_merges_actions_namespace(monkeypatch):
    _install(monkeypatch, patches=[
        _patch(1, "actions", "PolicyGuard", path="/actions/act_001", patch={
            "action_id": "act_001",
            "action_type": "runtime_tool",
            "tool_name": "restart_connector",
            "risk": "medium",
            "policy_decision": "require_approval",
        }),
        _patch(2, "actions", "ApprovalGate", path="/actions/act_001", patch={
            "approval_id": "appr_001",
            "approval_status": "approved",
            "execution_status": "pending",
        }),
    ])

    res = client.get("/api/v1/agent/runs/run_001/actions")

    actions = res.json()["data"]["actions"]
    assert actions == [{
        "action_id": "act_001",
        "action_type": "runtime_tool",
        "tool_name": "restart_connector",
        "risk": "medium",
        "policy_decision": "require_approval",
        "approval_id": "appr_001",
        "approval_status": "approved",
        "execution_status": "pending",
        "audit_event_id": None,
    }]


def test_auxiliary_routes_accept_db_record_values_for_closed_runs(monkeypatch):
    ts = _now()
    author_id = UUID("8a686502-fc55-4515-b186-396c19293edb")
    action_id = UUID("11111111-1111-1111-1111-111111111111")
    approval_id = UUID("22222222-2222-2222-2222-222222222222")
    audit_event_id = UUID("33333333-3333-3333-3333-333333333333")

    for status in ("completed", "failed"):
        event = SimpleNamespace(
            run_id="run_001",
            created_at=ts + timedelta(seconds=3),
            type="agent_completed",
            agent=author_id,
            message=None,
        )
        patches = [
            StatePatchRecord(
                run_id="run_001",
                seq=1,
                namespace="analysis",
                author=author_id,
                op="append",
                path="/analysis/root_cause",
                patch=json.dumps({"summary": "root cause"}),
                created_at=ts + timedelta(seconds=1),
            ),
            StatePatchRecord(
                run_id="run_001",
                seq=2,
                namespace="actions",
                author="PolicyGuard",
                op="append",
                path=f"/actions/{action_id}",
                patch={
                    "action_id": action_id,
                    "action_type": "runtime_tool",
                    "risk": "medium",
                    "approval_id": approval_id,
                    "audit_event_id": audit_event_id,
                },
                created_at=ts + timedelta(seconds=2),
            ),
        ]
        _install(monkeypatch, runs=[_run(status=status)], patches=patches, events=[event])

        summary = client.get("/api/v1/agent/runs/run_001/state/summary")
        assert summary.status_code == 200
        summary_data = summary.json()["data"]
        assert summary_data["status"] == status
        assert summary_data["namespaces"]["analysis"]["last_author"] == str(author_id)
        assert summary_data["namespaces"]["analysis"]["last_updated_at"].startswith("2026-06-01T00:00:01")

        timeline = client.get("/api/v1/agent/runs/run_001/timeline")
        assert timeline.status_code == 200
        timeline_items = timeline.json()["data"]["items"]
        assert timeline_items[0]["agent"] == str(author_id)
        assert timeline_items[0]["created_at"].startswith("2026-06-01T00:00:01")
        assert timeline_items[2]["message"] == ""

        steps = client.get("/api/v1/agent/runs/run_001/steps")
        assert steps.status_code == 200
        assert steps.json()["data"]["steps"][0]["agent"] == str(author_id)

        actions = client.get("/api/v1/agent/runs/run_001/actions")
        assert actions.status_code == 200
        action = actions.json()["data"]["actions"][0]
        assert action["action_id"] == str(action_id)
        assert action["approval_id"] == str(approval_id)
        assert action["audit_event_id"] == str(audit_event_id)
        assert action["risk"] == "medium"


def test_messages_starts_background_workflow(monkeypatch):
    run_repo = _install(monkeypatch)
    calls: list[dict[str, Any]] = []

    async def fake_run_workflow(**kwargs):
        calls.append(kwargs)

    monkeypatch.setattr(routes_runs, "run_workflow", fake_run_workflow)
    monkeypatch.setattr(routes_runs, "get_event_bus", lambda: object())
    monkeypatch.setattr(routes_runs, "get_tool_registry", lambda: object())

    res = client.post("/api/v1/agent/runs/run_001/messages", json={"message": "continue"})

    assert res.status_code == 200
    assert res.json()["data"] == {"run_id": "run_001", "status": "running"}
    assert run_repo.updates == [("run_001", "running", None)]
    assert calls[0]["run_id"] == "run_001"
    assert calls[0]["user_message"] == "continue"


def test_messages_blocked_when_run_closed(monkeypatch):
    _install(monkeypatch, runs=[_run(status="completed")])

    res = client.post("/api/v1/agent/runs/run_001/messages", json={"message": "continue"})

    body = res.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "RUN_ALREADY_CLOSED"


def test_cancel_sets_status(monkeypatch):
    run_repo = _install(monkeypatch)

    res = client.post("/api/v1/agent/runs/run_001/cancel")

    assert res.json()["data"] == {"run_id": "run_001", "status": "cancelled"}
    assert run_repo.updates == [("run_001", "cancelled", None)]


def test_retry_returns_not_implemented(monkeypatch):
    _install(monkeypatch)

    res = client.post("/api/v1/agent/runs/run_001/retry", json={"from_stage": "rca"})

    body = res.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "NOT_IMPLEMENTED"


def test_list_runs_filters_by_status(monkeypatch):
    _install(monkeypatch, runs=[
        _run("run_001", status="running"),
        _run("run_002", status="completed", created_at=_now() + timedelta(seconds=1)),
    ])

    res = client.get("/api/v1/agent/runs?status=completed")

    runs = res.json()["data"]["runs"]
    assert [run["run_id"] for run in runs] == ["run_002"]


def test_list_runs_limit_applies(monkeypatch):
    _install(monkeypatch, runs=[
        _run("run_001", created_at=_now()),
        _run("run_002", created_at=_now() + timedelta(seconds=1)),
        _run("run_003", created_at=_now() + timedelta(seconds=2)),
    ])

    res = client.get("/api/v1/agent/runs?limit=2")

    runs = res.json()["data"]["runs"]
    assert [run["run_id"] for run in runs] == ["run_003", "run_002"]
