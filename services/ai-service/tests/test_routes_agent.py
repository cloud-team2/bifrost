from __future__ import annotations

import json
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


def test_create_run_rejects_non_uuid_project_id(monkeypatch):
    """#592: 비 UUID project_id(예: demo-team)는 500 대신 VALIDATION_FAILED."""
    repo = FakeRunRepo()
    workflow_calls: list[dict[str, Any]] = []

    async def fake_run_workflow(**kwargs: Any) -> None:
        workflow_calls.append(kwargs)

    monkeypatch.setattr(routes_agent, "get_run_repo", lambda: repo)
    monkeypatch.setattr(routes_agent, "run_workflow", fake_run_workflow)
    monkeypatch.setattr(routes_agent, "get_event_bus", lambda: object())
    monkeypatch.setattr(routes_agent, "get_tool_registry", lambda: object())

    response = client.post("/api/v1/agent/runs", json={
        "project_id": "demo-team",
        "mode": "incident_analysis",
        "message": "조치 후보 보여줘",
        "remediation_requested": True,
    })

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is False
    assert body["error"]["code"] == "VALIDATION_FAILED"
    assert repo.created == []
    assert workflow_calls == []


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


def test_save_tool_turn_persists_user_and_tool_messages():
    # #860 명령 버튼(슬래시 커맨드) 결과를 thread에 저장(복원 전용).
    thread_id = "chat-tooltest-860"
    resp = client.post(
        f"/api/v1/agent/threads/{thread_id}/tool-turn",
        json={
            "project_id": "11111111-1111-1111-1111-111111111111",
            "owner": "ta@bifrost.io",
            "request_text": "커넥터 상태 조회해줘",
            "tool_name": "get_connector_status",
            "params": {"connector_name": "orders-sink"},
            "result": {"state": "RUNNING", "tasks": [{"task_id": 0, "state": "RUNNING"}]},
        },
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["saved"] is True

    messages = client.get(
        f"/api/v1/agent/threads/{thread_id}/messages"
    ).json()["data"]["messages"]
    # user 요청 + tool 결과 두 turn이 시간순으로 저장된다.
    assert [m["role"] for m in messages] == ["user", "tool"]
    assert messages[0]["content"] == "커넥터 상태 조회해줘"
    payload = json.loads(messages[1]["content"])
    assert payload["tool_name"] == "get_connector_status"
    assert payload["result"]["state"] == "RUNNING"


def test_save_tool_turn_ignores_non_chat_thread():
    # 자유 채팅(chat-*)이 아닌 thread(인시던트 등)는 저장 대상이 아니다.
    resp = client.post(
        "/api/v1/agent/threads/22222222-2222-2222-2222-222222222222/tool-turn",
        json={
            "project_id": "11111111-1111-1111-1111-111111111111",
            "request_text": "x",
            "tool_name": "get_connector_status",
            "params": {},
            "result": {},
        },
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["saved"] is False


def test_format_history_excludes_tool_role():
    # role='tool'(슬래시 결과)은 LLM 히스토리 블록에서 제외돼 컨텍스트를 오염시키지 않는다.
    from app.persistence.message_repository import AgentMessage
    from app.workflow.runner import _format_history

    block = _format_history([
        AgentMessage(id="1", thread_id="t", role="user", content="안녕"),
        AgentMessage(id="2", thread_id="t", role="tool", content='{"tool_name":"get_connector_status"}'),
        AgentMessage(id="3", thread_id="t", role="assistant", content="네 반갑습니다"),
    ])
    assert "안녕" in block
    assert "네 반갑습니다" in block
    assert "tool_name" not in block
    assert "get_connector_status" not in block
