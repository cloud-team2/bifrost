"""action_execution 트랙 통합 테스트."""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import routes_approvals
from app.main import app
from app.persistence.approval_link_repository import get_approval_repo
from app.persistence.change_ticket_repository import (
    STATUS_CHANGE_TICKET_REQUIRED,
    STATUS_CHANGE_WINDOW_REQUIRED,
    STATUS_IMPACT_ANALYSIS_REQUIRED,
    STATUS_ROLLBACK_PLAN_REQUIRED,
    STATUS_VERIFIER_PLAN_REQUIRED,
    STATUS_VERIFIED,
    get_change_ticket_repo,
)
from app.schemas.outputs import (
    ActionCandidateOutput,
    ApprovalGateOutput,
    ChangeManagementOutput,
    ChangeManagementRecordOutput,
    ExecutorOutput,
    PolicyDecisionOutput,
    PolicyGuardOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.schemas.state import (
    ActionCandidate,
    ActionStatus,
    ActionType,
    AgentMode,
    PolicyDecisionType,
    RiskLevel,
    RunStatus,
    VerificationStatus,
)
from app.schemas.tools import SpringErrorCode, ToolContext, ToolResult, ToolStatus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.streaming.event_bus import EventBus
from app.workflow.stages.approval_gate import run_approval_gate
from app.workflow.stages.change_gate import run_change_gate
from app.workflow.stages.executor import run_executor
from app.workflow.stages.policy_guard import run_policy_guard
from app.workflow.runner import run_workflow
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


def _action_execution_router_out():
    from app.schemas.outputs import RouteDecision, RouterOutput

    return RouterOutput(
        route_decision=RouteDecision(
            mode=AgentMode.ACTION_EXECUTION,
            remediation_requested=False,
            reason="test",
            required_flow=[],
        )
    )


def _change_policy_guard_out() -> PolicyGuardOutput:
    return PolicyGuardOutput(
        policy_decisions=[
            PolicyDecisionOutput(
                action_id="act_change_runner",
                action_type=ActionType.RUNTIME_TOOL,
                risk=RiskLevel.MEDIUM,
                decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
                status=ActionStatus.PENDING_APPROVAL,
                reason="중위험 조치 — 변경관리 티켓 필요",
                tool_name="pause_connector",
                tool_params={"connector_name": "orders-source-connector"},
            )
        ]
    )


def _verifier_out() -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id="verify-runner",
                target="action_execution",
                status=VerificationStatus.PASS,
                approved_for_final_response=True,
                reason="ok",
            )
        ]
    )


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


@pytest.mark.asyncio
async def test_run_workflow_defers_when_change_gate_waits():
    from app.persistence.event_repository import InMemoryEventRepository

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_policy_guard", new_callable=AsyncMock) as mock_policy_guard,
        patch("app.workflow.runner.run_approval_gate", new_callable=AsyncMock) as mock_approval_gate,
        patch("app.workflow.runner.run_change_gate", new_callable=AsyncMock) as mock_change_gate,
        patch("app.workflow.runner.run_executor", new_callable=AsyncMock) as mock_executor,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = InMemoryEventRepository()
        mock_router.return_value = _action_execution_router_out()
        mock_policy_guard.return_value = _change_policy_guard_out()
        mock_approval_gate.return_value = ApprovalGateOutput(approved_actions=[], run_status="running")
        mock_change_gate.return_value = ChangeManagementOutput(
            change_management_records=[
                ChangeManagementRecordOutput(
                    change_ticket_id="CHG-WAIT",
                    action_id="act_change_runner",
                    status=STATUS_CHANGE_WINDOW_REQUIRED,
                )
            ],
            run_status="waiting_for_approval",
        )

        await run_workflow(
            run_id="run_change_waits",
            user_message="조치 실행",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    run_repo.update_status.assert_any_await("run_change_waits", "waiting_for_approval", "change_gate")
    mock_executor.assert_not_awaited()
    assert not any(
        call.args == ("run_change_waits", "completed", None)
        for call in run_repo.update_status.await_args_list
    )


@pytest.mark.asyncio
async def test_run_workflow_passes_verified_change_action_to_executor():
    from app.persistence.event_repository import InMemoryEventRepository

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()
    captured_candidates = []

    async def _capture_executor(candidates, **kwargs):
        captured_candidates.extend(candidates)
        return ExecutorOutput(execution_results=[])

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_policy_guard", new_callable=AsyncMock) as mock_policy_guard,
        patch("app.workflow.runner.run_approval_gate", new_callable=AsyncMock) as mock_approval_gate,
        patch("app.workflow.runner.run_change_gate", new_callable=AsyncMock) as mock_change_gate,
        patch("app.workflow.runner.run_executor", new=AsyncMock(side_effect=_capture_executor)) as mock_executor,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = InMemoryEventRepository()
        mock_router.return_value = _action_execution_router_out()
        mock_policy_guard.return_value = _change_policy_guard_out()
        mock_approval_gate.return_value = ApprovalGateOutput(approved_actions=[], run_status="running")
        mock_change_gate.return_value = ChangeManagementOutput(
            change_management_records=[
                ChangeManagementRecordOutput(
                    change_ticket_id="CHG-VERIFIED",
                    action_id="act_change_runner",
                    status=STATUS_VERIFIED,
                )
            ],
            run_status="running",
        )
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "완료"

        await run_workflow(
            run_id="run_change_verified_exec",
            user_message="조치 실행",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    mock_executor.assert_awaited_once()
    assert len(captured_candidates) == 1
    candidate = captured_candidates[0]
    assert candidate.action_id == "act_change_runner"
    assert candidate.action_type == ActionType.RUNTIME_TOOL
    assert candidate.risk == RiskLevel.MEDIUM
    assert candidate.tool_name == "pause_connector"
    assert candidate.status == ActionStatus.READY


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


def test_approval_decision_api_approved_resumes_workflow(monkeypatch):
    repo = get_approval_repo()
    link = repo.create("run_api_resume", "act_api_resume", {})
    run = SimpleNamespace(
        project_id="11111111-1111-1111-1111-111111111111",
        incident_id="22222222-2222-2222-2222-222222222222",
        remediation_requested=True,
    )
    run_repo = SimpleNamespace(
        get=AsyncMock(return_value=run),
        update_status=AsyncMock(),
    )
    workflow_calls: list[dict] = []

    async def fake_run_workflow(**kwargs):
        workflow_calls.append(kwargs)

    monkeypatch.setattr(routes_approvals, "get_run_repo", lambda: run_repo)
    monkeypatch.setattr(routes_approvals, "run_workflow", fake_run_workflow)
    monkeypatch.setattr(routes_approvals, "get_event_bus", lambda: object())
    monkeypatch.setattr(routes_approvals, "get_tool_registry", lambda: object())

    resp = client.post(
        f"/api/v1/approvals/{link.approval_id}/decision",
        json={"decision": "approved", "comment": "operator approved"},
    )

    assert resp.status_code == 200
    run_repo.update_status.assert_awaited_once_with("run_api_resume", "running", "approval_gate")
    assert workflow_calls[0]["requested_mode"] == "approval_decision"
    assert workflow_calls[0]["requested_incident_id"] == "22222222-2222-2222-2222-222222222222"
    assert workflow_calls[0]["requested_remediation_requested"] is True


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


def test_approval_decision_api_rejected_completes_without_resuming(monkeypatch):
    repo = get_approval_repo()
    link = repo.create("run_api_reject_complete", "act_api_reject_complete", {})
    run_repo = SimpleNamespace(
        get=AsyncMock(return_value=SimpleNamespace()),
        update_status=AsyncMock(),
    )
    append_event = AsyncMock()
    workflow = AsyncMock()
    bus = SimpleNamespace(
        publish=AsyncMock(),
        close_run=AsyncMock(),
    )

    monkeypatch.setattr(routes_approvals, "get_run_repo", lambda: run_repo)
    monkeypatch.setattr(routes_approvals, "append_event", append_event)
    monkeypatch.setattr(routes_approvals, "get_event_repo", lambda: object())
    monkeypatch.setattr(routes_approvals, "get_event_bus", lambda: bus)
    monkeypatch.setattr(routes_approvals, "run_workflow", workflow)

    resp = client.post(
        f"/api/v1/approvals/{link.approval_id}/decision",
        json={"decision": "rejected"},
    )

    assert resp.status_code == 200
    workflow.assert_not_awaited()
    run_repo.update_status.assert_awaited_once_with(
        "run_api_reject_complete", "failed", "approval_gate"
    )
    published_event = bus.publish.await_args.args[1]
    assert published_event.payload == {
        "error": "approval_rejected",
        "action_id": "act_api_reject_complete",
    }
    bus.close_run.assert_awaited_once_with("run_api_reject_complete")
    append_event.assert_awaited_once()


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


def test_policy_matrix_runtime_medium_requires_approval():
    from app.catalogs import policy_matrix

    rule = policy_matrix.lookup(ActionType.RUNTIME_TOOL, RiskLevel.MEDIUM)

    assert rule.decision == PolicyDecisionType.REQUIRE_APPROVAL


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


@pytest.mark.asyncio
async def test_approval_gate_ignores_change_management_decision():
    from app.schemas.state import PolicyDecision

    run_id = "run_change_not_approval"
    decisions = [
        PolicyDecision(
            action_id="act_change_not_approval",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.MEDIUM,
            decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]

    out = await run_approval_gate(decisions, run_id)

    assert out.run_status == "running"
    assert out.approved_actions == []
    assert get_approval_repo().get_by_action(run_id, "act_change_not_approval") is None


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
    assert any(r.status == STATUS_CHANGE_TICKET_REQUIRED for r in out.change_management_records)


@pytest.mark.asyncio
async def test_change_gate_requires_ticket_when_missing_from_repository():
    from app.schemas.state import PolicyDecision

    decisions = [
        PolicyDecision(
            action_id="act_change_missing_repo",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.MEDIUM,
            decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]
    out = await run_change_gate(decisions, "run_change_missing_repo")
    assert out.run_status == "waiting_for_approval"
    assert out.change_management_records[0].status == STATUS_CHANGE_TICKET_REQUIRED


@pytest.mark.asyncio
async def test_change_gate_loads_persisted_ticket_and_marks_verified():
    from app.schemas.state import PolicyDecision

    repo = get_change_ticket_repo()
    await repo.upsert(
        "run_change_persisted",
        "act_change_persisted",
        "CHG-PERSISTED",
        window="2026-06-09T10:00Z/2026-06-09T11:00Z",
        rollback_plan="restore previous connector config",
        impact_analysis="connector task restart affects only tenant test workloads",
        verifier_plan="verify connector task health and absence of retry errors",
    )
    decisions = [
        PolicyDecision(
            action_id="act_change_persisted",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.MEDIUM,
            decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]

    out = await run_change_gate(decisions, "run_change_persisted")
    stored = await repo.get_by_action("run_change_persisted", "act_change_persisted")

    assert out.run_status == "running"
    assert out.change_management_records[0].status == STATUS_VERIFIED
    assert stored is not None
    assert stored.status == STATUS_VERIFIED


@pytest.mark.asyncio
async def test_change_gate_requires_rollback_plan_and_persists_status():
    from app.schemas.state import PolicyDecision

    repo = get_change_ticket_repo()
    await repo.upsert(
        "run_change_missing_rollback",
        "act_change_missing_rollback",
        "CHG-MISSING-ROLLBACK",
        window="2026-06-09T10:00Z/2026-06-09T11:00Z",
        rollback_plan=" ",
        impact_analysis="connector task restart affects only tenant test workloads",
        verifier_plan="verify connector task health and absence of retry errors",
    )
    decisions = [
        PolicyDecision(
            action_id="act_change_missing_rollback",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.MEDIUM,
            decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]

    out = await run_change_gate(decisions, "run_change_missing_rollback")
    stored = await repo.get_by_action("run_change_missing_rollback", "act_change_missing_rollback")

    assert out.run_status == "waiting_for_approval"
    assert out.change_management_records[0].status == STATUS_ROLLBACK_PLAN_REQUIRED
    assert stored is not None
    assert stored.status == STATUS_ROLLBACK_PLAN_REQUIRED


@pytest.mark.asyncio
async def test_change_gate_requires_impact_analysis_and_persists_status():
    from app.schemas.state import PolicyDecision

    repo = get_change_ticket_repo()
    await repo.upsert(
        "run_change_missing_impact",
        "act_change_missing_impact",
        "CHG-MISSING-IMPACT",
        window="2026-06-09T10:00Z/2026-06-09T11:00Z",
        rollback_plan="restore previous connector config",
        impact_analysis=" ",
        verifier_plan="verify connector task health and absence of retry errors",
    )
    decisions = [
        PolicyDecision(
            action_id="act_change_missing_impact",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.MEDIUM,
            decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]

    out = await run_change_gate(decisions, "run_change_missing_impact")
    stored = await repo.get_by_action("run_change_missing_impact", "act_change_missing_impact")

    assert out.run_status == "waiting_for_approval"
    assert out.change_management_records[0].status == STATUS_IMPACT_ANALYSIS_REQUIRED
    assert stored is not None
    assert stored.status == STATUS_IMPACT_ANALYSIS_REQUIRED


@pytest.mark.asyncio
async def test_change_gate_requires_verifier_plan_and_persists_status():
    from app.schemas.state import PolicyDecision

    repo = get_change_ticket_repo()
    await repo.upsert(
        "run_change_missing_verifier_plan",
        "act_change_missing_verifier_plan",
        "CHG-MISSING-VERIFIER-PLAN",
        window="2026-06-09T10:00Z/2026-06-09T11:00Z",
        rollback_plan="restore previous connector config",
        impact_analysis="connector task restart affects only tenant test workloads",
        verifier_plan=" ",
    )
    decisions = [
        PolicyDecision(
            action_id="act_change_missing_verifier_plan",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.MEDIUM,
            decision=PolicyDecisionType.REQUIRE_CHANGE_MANAGEMENT,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]

    out = await run_change_gate(decisions, "run_change_missing_verifier_plan")
    stored = await repo.get_by_action(
        "run_change_missing_verifier_plan",
        "act_change_missing_verifier_plan",
    )

    assert out.run_status == "waiting_for_approval"
    assert out.change_management_records[0].status == STATUS_VERIFIER_PLAN_REQUIRED
    assert stored is not None
    assert stored.status == STATUS_VERIFIER_PLAN_REQUIRED


@pytest.mark.asyncio
async def test_change_gate_ignores_human_approval_decisions():
    from app.schemas.state import PolicyDecision

    decisions = [
        PolicyDecision(
            action_id="act_human_approval",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.HIGH,
            decision=PolicyDecisionType.REQUIRE_APPROVAL,
            status=ActionStatus.PENDING_APPROVAL,
            reason="test",
        )
    ]

    out = await run_change_gate(decisions, "run_human_approval")

    assert out.run_status == "running"
    assert out.change_management_records == []


def test_change_ticket_api_persists_and_requeries_verified_status():
    run_id = "run_api_change_verified"
    payload = {
        "action_id": "act_api_change_verified",
        "ticket_id": "CHG-API-001",
        "window": "2026-06-09T10:00Z/2026-06-09T11:00Z",
        "rollback_plan": "restore previous connector config",
        "impact_analysis": "connector task restart affects only tenant test workloads",
        "verifier_plan": "verify connector task health and absence of retry errors",
    }

    submit = client.post(f"/api/v1/agent/runs/{run_id}/change-tickets", json=payload)
    listed = client.get(f"/api/v1/agent/runs/{run_id}/change-tickets")

    assert submit.status_code == 200
    submit_body = submit.json()
    assert submit_body["data"]["status"] == STATUS_VERIFIED
    assert submit_body["data"]["run_status"] == "running"
    assert submit_body["data"]["change_management_records"] == [
        {
            "change_ticket_id": "CHG-API-001",
            "action_id": "act_api_change_verified",
            "status": STATUS_VERIFIED,
        }
    ]

    assert listed.status_code == 200
    tickets = listed.json()["data"]["change_tickets"]
    assert len(tickets) == 1
    assert tickets[0]["ticket_id"] == "CHG-API-001"
    assert tickets[0]["status"] == STATUS_VERIFIED


def test_change_ticket_api_returns_gate_status_for_incomplete_ticket():
    run_id = "run_api_change_incomplete"

    resp = client.post(
        f"/api/v1/agent/runs/{run_id}/change-tickets",
        json={"action_id": "act_api_change_incomplete", "ticket_id": "CHG-API-002"},
    )
    listed = client.get(f"/api/v1/agent/runs/{run_id}/change-tickets")

    assert resp.status_code == 200
    body = resp.json()
    assert body["data"]["status"] == STATUS_CHANGE_WINDOW_REQUIRED
    assert body["data"]["run_status"] == "waiting_for_approval"
    assert listed.status_code == 200
    tickets = listed.json()["data"]["change_tickets"]
    assert len(tickets) == 1
    assert tickets[0]["status"] == STATUS_CHANGE_WINDOW_REQUIRED


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
