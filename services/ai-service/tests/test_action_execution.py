"""action_execution 트랙 통합 테스트."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.persistence.approval_link_repository import get_approval_repo
from app.schemas.outputs import (
    ActionCandidateOutput,
    PolicyDecisionOutput,
    PolicyGuardOutput,
)
from app.schemas.state import (
    ActionCandidate,
    ActionStatus,
    ActionType,
    AgentMode,
    PolicyDecisionType,
    RiskLevel,
    RunStatus,
)
from app.schemas.tools import SpringErrorCode, ToolContext, ToolResult, ToolStatus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.stages.approval_gate import run_approval_gate
from app.workflow.stages.change_gate import run_change_gate
from app.workflow.stages.executor import run_executor
from app.workflow.stages.policy_guard import run_policy_guard
from app.tools.registry import ToolClientRegistry

client = TestClient(app)


def _context() -> ToolContext:
    return ToolContext(
        run_id="run_test",
        step_id="step_test",
        agent_name="test",
        project_id="proj_test",
        request_id="req_test",
    )


def _supervisor() -> tuple[Supervisor, InMemoryStateStore]:
    store = InMemoryStateStore()
    policy = RetryPolicy(max_steps=30)
    return Supervisor(store=store, policy=policy), store


# ── Supervisor transitions ────────────────────────────────────────────────────

def test_action_execution_advances_through_stages():
    sup, store = _supervisor()
    sup.start_run("run_001", AgentMode.ACTION_EXECUTION)

    stages = []
    nxt = sup.advance("run_001")
    while nxt is not None:
        stages.append(nxt)
        nxt = sup.advance("run_001")

    assert stages == ["policy_guard", "approval_gate", "change_gate", "executor", "verifier", "report"]
    state = store.get("run_001")
    assert state is not None
    assert state.run.status == RunStatus.COMPLETED


def test_approval_decision_advances_through_stages():
    sup, store = _supervisor()
    sup.start_run("run_002", AgentMode.APPROVAL_DECISION)

    stages = []
    nxt = sup.advance("run_002")
    while nxt is not None:
        stages.append(nxt)
        nxt = sup.advance("run_002")

    assert stages == ["approval_gate", "executor", "verifier", "report"]
    state = store.get("run_002")
    assert state is not None
    assert state.run.status == RunStatus.COMPLETED


# ── Approval Decision API ─────────────────────────────────────────────────────

def test_approval_decision_api_approves():
    repo = get_approval_repo()
    link = repo.create("run_api_approved", "act_api_approved", {})

    resp = client.post(
        f"/api/v1/approvals/{link.approval_id}/decision",
        json={"decision": "approved", "comment": "ok"},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body == {
        "ok": True,
        "request_id": "",
        "data": {"approval_id": link.approval_id, "status": "approved"},
        "error": None,
    }
    assert repo.get(link.approval_id).status == "approved"


def test_approval_decision_api_rejects():
    repo = get_approval_repo()
    link = repo.create("run_api_rejected", "act_api_rejected", {})

    resp = client.post(
        f"/api/v1/approvals/{link.approval_id}/decision",
        json={"decision": "rejected"},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body == {
        "ok": True,
        "request_id": "",
        "data": {"approval_id": link.approval_id, "status": "rejected"},
        "error": None,
    }
    assert repo.get(link.approval_id).status == "rejected"


def test_approval_decision_api_rejects_unknown_decision():
    repo = get_approval_repo()
    link = repo.create("run_api_unknown", "act_api_unknown", {})

    resp = client.post(
        f"/api/v1/approvals/{link.approval_id}/decision",
        json={"decision": "deferred"},
    )

    assert resp.status_code == 400
    assert repo.get(link.approval_id).status == "pending"


def test_approval_decision_api_returns_404_for_missing_approval():
    resp = client.post(
        "/api/v1/approvals/missing-approval/decision",
        json={"decision": "approved"},
    )

    assert resp.status_code == 404


# ── Policy Guard ──────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_policy_guard_deny_becomes_blocked():
    from app.streaming.event_bus import EventBus
    from app.persistence.event_repository import InMemoryEventRepository

    bus = EventBus()
    repo = InMemoryEventRepository()

    candidates = [
        ActionCandidateOutput(
            action_id="act_001",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="forbidden_action",
            risk=RiskLevel.FORBIDDEN,
            reason="test",
            tool_name="restart_connector",
        )
    ]
    out = await run_policy_guard(candidates, bus=bus, event_repo=repo, run_id="run_deny")
    assert len(out.policy_decisions) == 1
    dec = out.policy_decisions[0]
    assert dec.decision == PolicyDecisionType.DENY
    assert dec.status == ActionStatus.BLOCKED


@pytest.mark.asyncio
async def test_policy_guard_high_risk_requires_approval():
    from app.streaming.event_bus import EventBus
    from app.persistence.event_repository import InMemoryEventRepository

    bus = EventBus()
    repo = InMemoryEventRepository()

    candidates = [
        ActionCandidateOutput(
            action_id="act_002",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="restart",
            risk=RiskLevel.HIGH,
            reason="test",
            tool_name="restart_connector",
        )
    ]
    out = await run_policy_guard(candidates, bus=bus, event_repo=repo, run_id="run_high")
    dec = out.policy_decisions[0]
    assert dec.decision == PolicyDecisionType.REQUIRE_APPROVAL
    assert dec.status == ActionStatus.PENDING_APPROVAL


@pytest.mark.asyncio
async def test_policy_guard_low_risk_allows():
    from app.streaming.event_bus import EventBus
    from app.persistence.event_repository import InMemoryEventRepository

    bus = EventBus()
    repo = InMemoryEventRepository()

    candidates = [
        ActionCandidateOutput(
            action_id="act_003",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="read_action",
            risk=RiskLevel.LOW,
            reason="test",
            tool_name="get_connector_status",
        )
    ]
    out = await run_policy_guard(candidates, bus=bus, event_repo=repo, run_id="run_low")
    dec = out.policy_decisions[0]
    assert dec.decision == PolicyDecisionType.ALLOW
    assert dec.status == ActionStatus.READY


# ── Approval Gate ─────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_approval_gate_stops_run_when_pending():
    from app.schemas.state import PolicyDecision

    decisions = [
        PolicyDecision(
            action_id="act_pending",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.HIGH,
            decision=PolicyDecisionType.REQUIRE_APPROVAL,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]
    out = await run_approval_gate(decisions, "run_pending")
    assert out.run_status == "waiting_for_approval"
    assert len(out.approved_actions) == 0


@pytest.mark.asyncio
async def test_approval_gate_passes_approved_action():
    from app.persistence.approval_link_repository import get_approval_repo
    from app.schemas.state import PolicyDecision

    repo = get_approval_repo()
    link = repo.create("run_approved", "act_approved", {})
    repo.approve(link.approval_id)

    decisions = [
        PolicyDecision(
            action_id="act_approved",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.HIGH,
            decision=PolicyDecisionType.REQUIRE_APPROVAL,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]
    out = await run_approval_gate(decisions, "run_approved")
    assert out.run_status == "running"
    assert len(out.approved_actions) == 1
    assert out.approved_actions[0].action_id == "act_approved"


# ── Change Gate ───────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_change_gate_requires_ticket_when_missing():
    from app.schemas.state import PolicyDecision

    decisions = [
        PolicyDecision(
            action_id="act_change",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.MEDIUM,
            decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]
    out = await run_change_gate(decisions, "run_change", change_tickets={})
    assert out.run_status == "waiting_for_approval"
    assert any(r.status == "CHANGE_TICKET_REQUIRED" for r in out.change_management_records)


# ── Executor ──────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_executor_skips_non_ready_actions():
    def handler(request: httpx.Request) -> httpx.Response:
        pytest.fail("Spring should not be called for non-ready actions")

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    candidates = [
        ActionCandidate(
            action_id="act_blocked",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="blocked",
            risk=RiskLevel.HIGH,
            reason="test",
            status=ActionStatus.BLOCKED,
            tool_name="restart_connector",
        )
    ]
    out = await run_executor(candidates, run_id="run_skip", context=_context(), registry=registry)
    assert len(out.execution_results) == 0


@pytest.mark.asyncio
async def test_executor_injects_idempotency_key():
    captured: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "ok": True, "request_id": "req_1", "operation": "restart_connector",
                "result": {"connector_name": "test-src", "action": "restart", "status": "ok"},
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    candidates = [
        ActionCandidate(
            action_id="act_exec",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="test-src-connector",   # connector_name으로 사용됨
            risk=RiskLevel.HIGH,
            reason="approved",
            status=ActionStatus.READY,
            tool_name="restart_connector",
        )
    ]
    out = await run_executor(candidates, run_id="run_idem", context=_context(), registry=registry)

    assert len(captured) == 1
    assert "X-Idempotency-Key" in captured[0].headers
    assert captured[0].headers["X-Idempotency-Key"] == "run_idem:act_exec"


@pytest.mark.asyncio
async def test_executor_records_before_after_evidence():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "ok": True, "request_id": "req_1", "operation": "restart_connector",
                "result": {"connector_name": "test-src", "action": "restart", "status": "ok"},
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    candidates = [
        ActionCandidate(
            action_id="act_ev",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="test-src-connector",   # connector_name으로 사용됨
            risk=RiskLevel.HIGH,
            reason="approved",
            status=ActionStatus.READY,
            tool_name="restart_connector",
        )
    ]
    out = await run_executor(candidates, run_id="run_ev", context=_context(), registry=registry)

    assert len(out.execution_results) == 1
    result = out.execution_results[0]
    assert result.before_evidence_id is not None
    assert result.after_evidence_id is not None
    assert result.status == ActionStatus.COMPLETED
