from __future__ import annotations

from typing import Any

from fastapi.testclient import TestClient

from app.api import routes_agent
from app.main import app


client = TestClient(app)


class FakeRunRepo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []

    async def create(self, run_id: str, mode: str, **kwargs: Any):
        record = {"run_id": run_id, "mode": mode, **kwargs}
        self.created.append(record)
        return record

    async def get(self, run_id: str):
        return None


def test_create_run_passes_incident_context_to_background_workflow(monkeypatch):
    repo = FakeRunRepo()
    workflow_calls: list[dict[str, Any]] = []

    async def fake_run_workflow(**kwargs: Any) -> None:
        workflow_calls.append(kwargs)

    monkeypatch.setattr(routes_agent, "get_run_repo", lambda: repo)
    monkeypatch.setattr(routes_agent, "run_workflow", fake_run_workflow)
    monkeypatch.setattr(routes_agent, "get_event_bus", lambda: object())
    monkeypatch.setattr(routes_agent, "get_tool_registry", lambda: object())

    response = client.post("/api/v1/agent/runs", json={
        "project_id": "11111111-1111-1111-1111-111111111111",
        "mode": "incident_analysis",
        "message": "장애 상세 분석",
        "incident_id": "22222222-2222-2222-2222-222222222222",
        "remediation_requested": True,
        "stream": True,
    })

    assert response.status_code == 200
    assert repo.created[0]["mode"] == "incident_analysis"
    assert repo.created[0]["incident_id"] == "22222222-2222-2222-2222-222222222222"
    assert repo.created[0]["remediation_requested"] is True
    assert workflow_calls[0]["requested_mode"] == "incident_analysis"
    assert workflow_calls[0]["requested_incident_id"] == "22222222-2222-2222-2222-222222222222"
    assert workflow_calls[0]["requested_remediation_requested"] is True


def test_create_run_passes_action_candidate_to_background_workflow(monkeypatch):
    repo = FakeRunRepo()
    workflow_calls: list[dict[str, Any]] = []

    async def fake_run_workflow(**kwargs: Any) -> None:
        workflow_calls.append(kwargs)

    monkeypatch.setattr(routes_agent, "get_run_repo", lambda: repo)
    monkeypatch.setattr(routes_agent, "run_workflow", fake_run_workflow)
    monkeypatch.setattr(routes_agent, "get_event_bus", lambda: object())
    monkeypatch.setattr(routes_agent, "get_tool_registry", lambda: object())

    action_candidate = {
        "action_id": "act_resume",
        "action_type": "runtime_tool",
        "action_name": "resume_connector",
        "root_cause_id": "CONNECTOR_PAUSED",
        "risk": "low",
        "reason": "connector pause cleared",
        "expected_effect": "resume connector",
        "rollback_plan": "pause connector again",
        "estimated_duration": "1m",
        "tool_name": "resume_connector",
        "tool_params": {"connector_name": "orders-source"},
    }

    response = client.post("/api/v1/agent/runs", json={
        "project_id": "11111111-1111-1111-1111-111111111111",
        "mode": "action_execution",
        "message": "Run resume",
        "incident_id": "22222222-2222-2222-2222-222222222222",
        "stream": True,
        "action_candidate": action_candidate,
    })

    assert response.status_code == 200
    assert workflow_calls[0]["requested_mode"] == "action_execution"
    assert workflow_calls[0]["requested_action_candidate"].action_id == "act_resume"
    assert workflow_calls[0]["requested_action_candidate"].tool_params == {
        "connector_name": "orders-source"
    }
