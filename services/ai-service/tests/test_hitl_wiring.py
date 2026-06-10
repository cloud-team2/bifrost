"""#454 HITL 조치 실행 배선 회귀 테스트.

- router 모드 선택(remediation_requested / approval_decision)
- State 재사용: 제안(remediation) → 승인(approval) → 실행(executor) end-to-end
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from app.agents.router import run_router
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
    ActionStatus,
    ActionType,
    AgentMode,
    PolicyDecisionType,
    RiskLevel,
    VerificationStatus,
)
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.runner import run_workflow


# ── Router 모드 선택 ───────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_router_restart_intent_is_action_execution():
    out = await run_router("그럼 컨슈머 재시작해줘")
    assert out.route_decision.mode == AgentMode.ACTION_EXECUTION
    assert out.route_decision.remediation_requested is False
    assert out.route_decision.reuse_existing_analysis is True


@pytest.mark.asyncio
async def test_router_approve_intent_is_approval_decision():
    out = await run_router("승인할게")
    assert out.route_decision.mode == AgentMode.APPROVAL_DECISION


@pytest.mark.asyncio
async def test_router_reject_intent_is_approval_decision():
    out = await run_router("그 조치 거절")
    assert out.route_decision.mode == AgentMode.APPROVAL_DECISION


@pytest.mark.asyncio
async def test_router_remediation_request_sets_flag():
    out = await run_router("lag 원인 보고 조치 후보 보여줘")
    assert out.route_decision.mode == AgentMode.INCIDENT_ANALYSIS
    assert out.route_decision.remediation_requested is True
    # required_flow에 remediation/policy_guard 제안 단계가 포함된다.
    assert "remediation" in out.route_decision.required_flow
    assert "policy_guard" in out.route_decision.required_flow


@pytest.mark.asyncio
async def test_router_diagnose_only_no_remediation():
    out = await run_router("왜 lag가 늘었어?")
    assert out.route_decision.mode == AgentMode.INCIDENT_ANALYSIS
    assert out.route_decision.remediation_requested is False
    assert "remediation" not in out.route_decision.required_flow


@pytest.mark.asyncio
async def test_router_simple_query_default():
    out = await run_router("DLQ가 뭐야?")
    assert out.route_decision.mode == AgentMode.SIMPLE_QUERY


# ── State 재사용 end-to-end ────────────────────────────────────────────────────

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
async def test_approval_decision_reuses_prior_candidates_and_executes():
    """제안 turn이 남긴 후보를 approval_decision turn이 복원해 승인된 조치를 실행한다."""
    run_id = "run_hitl_e2e"
    state_repo = InMemoryStateRepository()

    # 이전 turn(incident_analysis + remediation)이 남긴 State patch 재현.
    await state_repo.append(
        run_id, "actions", "Remediation", "append", "/actions/candidates",
        {"candidates": [{
            "action_id": "act_restart",
            "action_type": "runtime_tool",
            "action_name": "orders-source-connector",
            "root_cause_id": "rc_1",
            "risk": "high",
            "reason": "connector failed",
            "tool_name": "restart_connector",
        }]},
    )
    await state_repo.append(
        run_id, "actions", "PolicyGuard", "append", "/actions/policy_decisions",
        {"policy_decisions": [{
            "action_id": "act_restart",
            "action_type": "runtime_tool",
            "risk": "high",
            "decision": "require_approval",
            "status": "pending_approval",
            "reason": "high risk",
            "tool_name": "restart_connector",
        }]},
    )

    # 사용자가 승인 링크를 승인한 상태.
    approval_repo = get_approval_repo()
    link = approval_repo.create(run_id, "act_restart", {})
    approval_repo.approve(link.approval_id)

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()
    captured: list = []

    async def _capture_executor(candidates, **kwargs):
        captured.extend(candidates)
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

    # approval_gate가 승인된 후보를 통과시키고 executor가 복원된 후보로 실행됨.
    assert len(captured) == 1
    candidate = captured[0]
    assert candidate.action_id == "act_restart"
    assert candidate.tool_name == "restart_connector"
    assert candidate.status == ActionStatus.READY


@pytest.mark.asyncio
async def test_action_execution_reuses_candidates_for_policy_guard():
    """action_execution turn이 빈 list 대신 복원된 후보로 Policy Guard를 호출한다."""
    run_id = "run_hitl_action"
    state_repo = InMemoryStateRepository()
    await state_repo.append(
        run_id, "actions", "Remediation", "append", "/actions/candidates",
        {"candidates": [{
            "action_id": "act_low",
            "action_type": "runtime_tool",
            "action_name": "orders-source-connector",
            "risk": "low",
            "reason": "restart",
            "tool_name": "get_connector_status",
        }]},
    )

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()
    seen: list = []

    async def _capture_policy(candidates, **kwargs):
        seen.extend(candidates)
        from app.schemas.outputs import PolicyGuardOutput
        return PolicyGuardOutput(policy_decisions=[])

    def _router_out() -> RouterOutput:
        return RouterOutput(route_decision=RouteDecision(
            mode=AgentMode.ACTION_EXECUTION, remediation_requested=False,
            reason="exec", required_flow=[],
        ))

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_policy_guard", new=AsyncMock(side_effect=_capture_policy)),
        patch("app.workflow.runner.run_executor", new_callable=AsyncMock) as mock_exec,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = InMemoryEventRepository()
        mock_router.return_value = _router_out()
        mock_exec.return_value = ExecutorOutput(execution_results=[])
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "완료"

        await run_workflow(
            run_id=run_id,
            user_message="재시작해줘",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    # Policy Guard가 빈 list가 아닌 복원된 후보를 받았는지 검증.
    assert len(seen) == 1
    assert seen[0].action_id == "act_low"
    assert seen[0].tool_name == "get_connector_status"
