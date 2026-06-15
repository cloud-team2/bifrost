from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from fastapi.testclient import TestClient

from app.api import routes_actions
from app.main import app


client = TestClient(app)


class FakeRunRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []
        self.runs: dict[str, Any] = {}
        self.updates: list[tuple[str, str, str | None]] = []

    async def create(self, run_id: str, mode: str, **kwargs: Any):
        record = {"run_id": run_id, "mode": mode, **kwargs}
        self.created.append(record)
        return record

    async def get(self, run_id: str):
        return self.runs.get(run_id)

    async def update_status(self, run_id: str, status: str, current_agent: str | None = None) -> None:
        self.updates.append((run_id, status, current_agent))


def test_action_run_passes_selected_candidate_to_background_workflow(monkeypatch):
    repo = FakeRunRepo()
    workflow_calls: list[dict[str, Any]] = []

    async def fake_run_workflow(**kwargs: Any) -> None:
        workflow_calls.append(kwargs)

    monkeypatch.setattr(routes_actions, "get_run_repo", lambda: repo)
    monkeypatch.setattr(routes_actions, "run_workflow", fake_run_workflow)
    monkeypatch.setattr(routes_actions, "get_event_bus", lambda: object())
    monkeypatch.setattr(routes_actions, "get_tool_registry", lambda: object())

    action_candidate = {
        "action_id": "act_restart",
        "action_type": "runtime_tool",
        "action_name": "restart_connector",
        "root_cause_id": "CONNECTOR_TASK_FAILED",
        "risk": "medium",
        "reason": "connector task failed",
        "tool_name": "restart_connector",
        "tool_params": {"connector_name": "orders-source"},
    }

    response = client.post("/api/v1/agent/actions/run", json={
        "project_id": "11111111-1111-1111-1111-111111111111",
        "incident_id": "22222222-2222-2222-2222-222222222222",
        "message": "Run restart",
        "action_candidates": [action_candidate],
    })

    assert response.status_code == 200
    assert repo.created[0]["mode"] == "action_execution"
    assert repo.created[0]["incident_id"] == "22222222-2222-2222-2222-222222222222"
    assert workflow_calls[0]["requested_mode"] == "action_execution"
    assert workflow_calls[0]["requested_incident_id"] == "22222222-2222-2222-2222-222222222222"
    assert workflow_calls[0]["requested_action_candidate"].action_id == "act_restart"
    assert workflow_calls[0]["requested_action_candidate"].tool_params == {
        "connector_name": "orders-source"
    }


def test_approval_decision_resumes_source_run_with_incident_context(monkeypatch):
    repo = FakeRunRepo()
    repo.runs["run_existing"] = SimpleNamespace(
        run_id="run_existing",
        project_id="11111111-1111-1111-1111-111111111111",
        incident_id="22222222-2222-2222-2222-222222222222",
        remediation_requested=True,
    )
    workflow_calls: list[dict[str, Any]] = []

    async def fake_run_workflow(**kwargs: Any) -> None:
        workflow_calls.append(kwargs)

    monkeypatch.setattr(routes_actions, "get_run_repo", lambda: repo)
    monkeypatch.setattr(routes_actions, "run_workflow", fake_run_workflow)
    monkeypatch.setattr(routes_actions, "get_event_bus", lambda: object())
    monkeypatch.setattr(routes_actions, "get_tool_registry", lambda: object())

    response = client.post("/api/v1/agent/actions/approval-decision", json={
        "project_id": "11111111-1111-1111-1111-111111111111",
        "run_id": "run_existing",
        "message": "approved",
    })

    assert response.status_code == 200
    assert response.json()["data"]["run_id"] == "run_existing"
    assert repo.created == []
    assert repo.updates == [("run_existing", "running", "approval_gate")]
    assert workflow_calls[0]["run_id"] == "run_existing"
    assert workflow_calls[0]["requested_mode"] == "approval_decision"
    assert workflow_calls[0]["requested_incident_id"] == "22222222-2222-2222-2222-222222222222"
    assert workflow_calls[0]["requested_remediation_requested"] is True
