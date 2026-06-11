"""#475 Executor X-Approval-Id 전달 회귀 테스트.

- ToolContext.spring_headers 가 approval_id/change_ticket_id 가 있을 때만
  X-Approval-Id / X-Change-Ticket-Id 헤더를 emit 하는지.
- Executor 가 승인된 action 실행 시 approval_id 를 per-action 으로 ToolContext 에
  실어 Spring mutation 호출 헤더에 전달하는지(fake transport 로 헤더 캡처).
- 매핑이 없으면(예: read tool) X-Approval-Id 미전송.
- Spring 이 APPROVAL_REQUIRED 로 거부하면 executor result 에 정확히 반영되는지.
- Runner 가 auto_/PENDING_ 센티넬을 실제 승인/티켓으로 오인하지 않고 제외하는지.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app.persistence.approval_link_repository import get_approval_repo
from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.state_repository import InMemoryStateRepository
from app.schemas.outputs import (
    ExecutorOutput,
    RouteDecision,
    RouterOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.schemas.state import (
    ActionCandidate,
    ActionStatus,
    ActionType,
    AgentMode,
    RiskLevel,
    VerificationStatus,
)
from app.schemas.tools import ToolContext
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.tools.registry import ToolClientRegistry
from app.workflow.runner import run_workflow
from app.workflow.stages.executor import run_executor


def _context(**overrides) -> ToolContext:
    base = dict(
        run_id="run_test",
        step_id="step_test",
        agent_name="test",
        project_id="proj_test",
        request_id="req_test",
    )
    base.update(overrides)
    return ToolContext(**base)


def _ok_handler(captured: list[httpx.Request]):
    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "ok": True, "request_id": "req_1", "operation": "restart_connector",
                "result": {"connector_name": "test-src", "action": "restart", "status": "ok"},
            },
        )

    return handler


def _ready_restart_candidate() -> ActionCandidate:
    return ActionCandidate(
        action_id="act_exec",
        action_type=ActionType.RUNTIME_TOOL,
        action_name="test-src-connector",
        risk=RiskLevel.HIGH,
        reason="approved",
        status=ActionStatus.READY,
        tool_name="restart_connector",
    )


# ── ToolContext.spring_headers ────────────────────────────────────────────────

def test_spring_headers_includes_approval_id_when_set():
    headers = _context(approval_id="appr-123").spring_headers()
    assert headers["X-Approval-Id"] == "appr-123"
    assert "X-Change-Ticket-Id" not in headers


def test_spring_headers_omits_approval_id_when_unset():
    headers = _context().spring_headers()
    assert "X-Approval-Id" not in headers
    assert "X-Change-Ticket-Id" not in headers


def test_spring_headers_includes_change_ticket_id_when_set():
    headers = _context(change_ticket_id="chg-999").spring_headers()
    assert headers["X-Change-Ticket-Id"] == "chg-999"
    assert "X-Approval-Id" not in headers


def test_with_approval_sets_identifiers_and_is_noop_when_empty():
    ctx = _context()
    enriched = ctx.with_approval(approval_id="a1", change_ticket_id="c1")
    assert enriched.approval_id == "a1"
    assert enriched.change_ticket_id == "c1"
    # 원본 불변
    assert ctx.approval_id is None
    # 빈 입력은 동일 객체 반환(불필요한 copy 방지)
    assert ctx.with_approval() is ctx


# ── Executor → Spring 헤더 전달 ────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_executor_sends_approval_header_for_approved_action():
    captured: list[httpx.Request] = []
    registry = ToolClientRegistry(transport=httpx.MockTransport(_ok_handler(captured)))

    out = await run_executor(
        [_ready_restart_candidate()],
        run_id="run_appr",
        context=_context(),
        registry=registry,
        approval_by_action={"act_exec": "appr-uuid-xyz"},
    )

    assert len(captured) == 1
    assert captured[0].headers["X-Approval-Id"] == "appr-uuid-xyz"
    # idempotency 도 여전히 동작
    assert captured[0].headers["X-Idempotency-Key"] == "run_appr:act_exec"
    assert out.execution_results[0].status == ActionStatus.COMPLETED


@pytest.mark.asyncio
async def test_executor_sends_change_ticket_header_when_mapped():
    captured: list[httpx.Request] = []
    registry = ToolClientRegistry(transport=httpx.MockTransport(_ok_handler(captured)))

    await run_executor(
        [_ready_restart_candidate()],
        run_id="run_chg",
        context=_context(),
        registry=registry,
        approval_by_action={"act_exec": "appr-1"},
        change_ticket_by_action={"act_exec": "chg-ticket-1"},
    )

    assert captured[0].headers["X-Approval-Id"] == "appr-1"
    assert captured[0].headers["X-Change-Ticket-Id"] == "chg-ticket-1"


@pytest.mark.asyncio
async def test_executor_omits_approval_header_without_mapping():
    captured: list[httpx.Request] = []
    registry = ToolClientRegistry(transport=httpx.MockTransport(_ok_handler(captured)))

    await run_executor(
        [_ready_restart_candidate()],
        run_id="run_no_appr",
        context=_context(),
        registry=registry,
    )

    assert len(captured) == 1
    assert "X-Approval-Id" not in captured[0].headers
    assert "X-Change-Ticket-Id" not in captured[0].headers


@pytest.mark.asyncio
async def test_executor_reflects_spring_approval_required_rejection():
    """Spring 이 APPROVAL_REQUIRED 로 거부하면 executor result 에 BLOCKED + reason_code 반영."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "ok": False, "request_id": "req_1", "operation": "restart_connector",
                "error": {"code": "APPROVAL_REQUIRED", "message": "approval required"},
            },
        )

    registry = ToolClientRegistry(transport=httpx.MockTransport(handler))
    out = await run_executor(
        [_ready_restart_candidate()],
        run_id="run_rej",
        context=_context(),
        registry=registry,
        approval_by_action={"act_exec": "stale-or-invalid"},
    )

    assert len(out.execution_results) == 1
    result = out.execution_results[0]
    assert result.status == ActionStatus.BLOCKED
    assert result.reason_code == "APPROVAL_REQUIRED"


# ── Runner 배선: 실제 approval_id 매핑 + 센티넬 제외 ────────────────────────────

def _approval_router_out() -> RouterOutput:
    return RouterOutput(
        route_decision=RouteDecision(
            mode=AgentMode.APPROVAL_DECISION,
            remediation_requested=False,
            reason="approve",
            required_flow=[],
        )
    )


def _verifier_out() -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id="v1",
                target="action_execution",
                status=VerificationStatus.PASS,
                approved_for_final_response=True,
                reason="ok",
            )
        ]
    )


@pytest.mark.asyncio
async def test_runner_maps_real_approval_id_and_excludes_auto_sentinel():
    """REQUIRE_APPROVAL → approved action 은 실제 approval_id 매핑, ALLOW(auto_)는 제외."""
    run_id = "run_appr_map"
    state_repo = InMemoryStateRepository()

    await state_repo.append(
        run_id, "actions", "Remediation", "append", "/actions/candidates",
        {"candidates": [
            {
                "action_id": "act_approved",
                "action_type": "runtime_tool",
                "action_name": "orders-source-connector",
                "risk": "high",
                "reason": "connector failed",
                "tool_name": "restart_connector",
            },
            {
                "action_id": "act_auto",
                "action_type": "runtime_tool",
                "action_name": "orders-source-connector",
                "risk": "low",
                "reason": "low risk read",
                "tool_name": "get_connector_status",
            },
        ]},
    )
    await state_repo.append(
        run_id, "actions", "PolicyGuard", "append", "/actions/policy_decisions",
        {"policy_decisions": [
            {
                "action_id": "act_approved",
                "action_type": "runtime_tool",
                "risk": "high",
                "decision": "require_approval",
                "status": "pending_approval",
                "reason": "high risk",
                "tool_name": "restart_connector",
            },
            {
                "action_id": "act_auto",
                "action_type": "runtime_tool",
                "risk": "low",
                "decision": "allow",
                "status": "ready",
                "reason": "low risk",
                "tool_name": "get_connector_status",
            },
        ]},
    )

    approval_repo = get_approval_repo()
    link = approval_repo.create(run_id, "act_approved", {})
    approval_repo.approve(link.approval_id)

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()
    captured_kwargs: dict = {}

    async def _capture_executor(candidates, **kwargs):
        captured_kwargs.update(kwargs)
        return ExecutorOutput(execution_results=[])

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_executor", new=AsyncMock(side_effect=_capture_executor)),
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = InMemoryEventRepository()
        mock_router.return_value = _approval_router_out()
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "완료"

        await run_workflow(
            run_id=run_id,
            user_message="승인할게",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    approval_by_action = captured_kwargs.get("approval_by_action")
    assert approval_by_action == {"act_approved": link.approval_id}
    # auto_ 센티넬은 실제 approval 이 아니므로 헤더로 전달되지 않는다.
    assert "act_auto" not in approval_by_action
