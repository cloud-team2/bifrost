"""#454 HITL 조치 실행 배선 회귀 테스트.

- router 모드 선택(remediation_requested / approval_decision)
- State 재사용: 제안(remediation) → 승인(approval) → 실행(executor) end-to-end
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agents.router import run_router
from app.persistence.approval_link_repository import get_approval_repo
from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.run_repository import InMemoryRunRepository
from app.persistence.state_repository import InMemoryStateRepository
from app.schemas.outputs import (
    ActionCandidateOutput,
    ExecutorOutput,
    PolicyDecisionOutput,
    PolicyGuardOutput,
    RemediationOutput,
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
from app.workflow.runner import _coerce_requested_action_candidate, _ready_action_candidate, run_workflow


class _FakeToolDefinition:
    def __init__(
        self,
        *,
        name: str = "restart_connector",
        risk: RiskLevel = RiskLevel.HIGH,
        required_param: str = "connector_name",
    ) -> None:
        self.name = name
        self.risk = risk
        self.required_param = required_param

    def validate_params(self, params: dict) -> dict:
        if self.required_param not in params:
            raise ValueError(f"missing {self.required_param}")
        return params


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


def test_requested_action_candidate_rejects_non_runtime_tool():
    registry = MagicMock()
    registry.get_definition.return_value = _FakeToolDefinition()

    candidate = _coerce_requested_action_candidate(
        {
            "action_id": "act_notify",
            "action_type": "notification",
            "action_name": "notify_with_tool_name",
            "risk": "low",
            "reason": "malformed direct request",
            "tool_name": "restart_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        },
        registry,
    )

    assert candidate is not None
    assert candidate.risk == RiskLevel.FORBIDDEN


def test_requested_action_candidate_rejects_missing_tool_params():
    registry = MagicMock()
    registry.get_definition.return_value = _FakeToolDefinition(
        name="pause_connector",
        risk=RiskLevel.MEDIUM,
    )

    candidate = _coerce_requested_action_candidate(
        {
            "action_id": "act_missing_params",
            "action_type": "runtime_tool",
            "action_name": "pause_connector",
            "risk": "medium",
            "reason": "missing explicit target",
            "tool_name": "pause_connector",
        },
        registry,
    )

    assert candidate is not None
    assert candidate.risk == RiskLevel.FORBIDDEN


def test_requested_action_candidate_rejects_unknown_tool():
    registry = MagicMock()
    registry.get_definition.return_value = None

    candidate = _coerce_requested_action_candidate(
        {
            "action_id": "act_unknown",
            "action_type": "runtime_tool",
            "action_name": "unknown",
            "risk": "low",
            "reason": "unknown tool",
            "tool_name": "delete_everything",
            "tool_params": {"connector_name": "orders-source-connector"},
        },
        registry,
    )

    assert candidate is not None
    assert candidate.risk == RiskLevel.FORBIDDEN


def test_requested_action_candidate_allows_restart_with_high_risk_approval():
    # #593: restart_connector는 allowlist에 포함되어 FORBIDDEN 강등 없이
    # HIGH risk 그대로 approval gate를 거친다.
    registry = MagicMock()
    registry.get_definition.return_value = _FakeToolDefinition(
        name="restart_connector",
        risk=RiskLevel.HIGH,
    )

    candidate = _coerce_requested_action_candidate(
        {
            "action_id": "act_restart",
            "action_type": "runtime_tool",
            "action_name": "restart_connector_task",
            "risk": "high",
            "reason": "user requested connector restart",
            "tool_name": "restart_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        },
        registry,
    )

    assert candidate is not None
    assert candidate.risk == RiskLevel.HIGH


def test_requested_action_candidate_allows_consumer_group_restart():
    registry = MagicMock()
    registry.get_definition.return_value = _FakeToolDefinition(
        name="restart_consumer_group",
        risk=RiskLevel.HIGH,
        required_param="consumer_group",
    )

    candidate = _coerce_requested_action_candidate(
        {
            "action_id": "act_restart_group",
            "action_type": "runtime_tool",
            "action_name": "restart_consumer_group",
            "risk": "high",
            "reason": "user requested consumer group restart",
            "tool_name": "restart_consumer_group",
            "tool_params": {"consumer_group": "orders-consumer"},
        },
        registry,
    )

    assert candidate is not None
    assert candidate.risk == RiskLevel.HIGH


def test_requested_action_candidate_escalates_medium_tool_to_human_approval():
    registry = MagicMock()
    registry.get_definition.return_value = _FakeToolDefinition(
        name="resume_connector",
        risk=RiskLevel.MEDIUM,
    )

    candidate = _coerce_requested_action_candidate(
        {
            "action_id": "act_resume",
            "action_type": "runtime_tool",
            "action_name": "resume_connector",
            "risk": "low",
            "reason": "selected report action",
            "tool_name": "resume_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        },
        registry,
    )

    assert candidate is not None
    assert candidate.risk == RiskLevel.HIGH


def test_ready_action_candidate_rejects_runtime_candidate_without_tool_params():
    remediation = RemediationOutput(action_candidates=[
        ActionCandidateOutput(
            action_id="act_no_params",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="orders-source-connector",
            risk=RiskLevel.HIGH,
            reason="missing target params",
            tool_name="pause_connector",
        )
    ])

    assert _ready_action_candidate("act_no_params", remediation, []) is None


def test_ready_action_candidate_allows_restart_tool():
    # #593: restart_connector가 allowlist에 포함되어 executor-ready 후보가 된다.
    remediation = RemediationOutput(action_candidates=[
        ActionCandidateOutput(
            action_id="act_restart",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="restart_connector_task",
            risk=RiskLevel.HIGH,
            reason="approved connector restart",
            tool_name="restart_connector",
            tool_params={"connector_name": "orders-source-connector"},
        )
    ])

    ready = _ready_action_candidate("act_restart", remediation, [])
    assert ready is not None
    assert ready.tool_name == "restart_connector"
    assert ready.status == ActionStatus.READY


def test_ready_action_candidate_allows_consumer_group_restart_tool():
    remediation = RemediationOutput(action_candidates=[
        ActionCandidateOutput(
            action_id="act_restart_group",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="restart_consumer_group",
            risk=RiskLevel.HIGH,
            reason="approved consumer group restart",
            tool_name="restart_consumer_group",
            tool_params={"consumer_group": "orders-consumer"},
        )
    ])

    ready = _ready_action_candidate("act_restart_group", remediation, [])
    assert ready is not None
    assert ready.tool_name == "restart_consumer_group"
    assert ready.status == ActionStatus.READY


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
            "action_id": "act_pause",
            "action_type": "runtime_tool",
            "action_name": "pause_connector",
            "root_cause_id": "rc_1",
            "risk": "high",
            "reason": "connector failed",
            "tool_name": "pause_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        }]},
    )
    await state_repo.append(
        run_id, "actions", "PolicyGuard", "append", "/actions/policy_decisions",
        {"policy_decisions": [{
            "action_id": "act_pause",
            "action_type": "runtime_tool",
            "risk": "high",
            "decision": "require_approval",
            "status": "pending_approval",
            "reason": "high risk",
            "tool_name": "pause_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        }]},
    )

    # 사용자가 승인 링크를 승인한 상태.
    approval_repo = get_approval_repo()
    link = approval_repo.create(
        run_id,
        "act_pause",
        {
            "tool_name": "pause_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        },
    )
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
    assert candidate.action_id == "act_pause"
    assert candidate.tool_name == "pause_connector"
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


@pytest.mark.asyncio
async def test_cross_turn_new_run_reuses_prior_incident_candidates():
    """FE가 메시지마다 새 run을 만들어도(#479) 같은 incident_id 직전 run의 조치
    후보를 복원해 승인된 조치를 실행한다.

    run A("조치 후보 보여줘")가 후보·policy State를 남기고, run B("승인할게")는
    빈 State로 들어오지만 incident_id로 run A를 찾아 후보를 재사용한다.
    """
    incident_id = "inc_cross_turn"
    run_a = "run_propose_A"
    run_b = "run_approve_B"

    run_repo = InMemoryRunRepository()
    await run_repo.create(run_a, "incident_analysis", incident_id=incident_id)
    await run_repo.create(run_b, "approval_decision", incident_id=incident_id)

    # run A(이전 turn)가 남긴 State patch — run_id=A로만 저장된다.
    state_repo = InMemoryStateRepository()
    await state_repo.append(
        run_a, "actions", "Remediation", "append", "/actions/candidates",
        {"candidates": [{
            "action_id": "act_pause",
            "action_type": "runtime_tool",
            "action_name": "pause_connector",
            "root_cause_id": "rc_1",
            "risk": "high",
            "reason": "connector failed",
            "tool_name": "pause_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        }]},
    )
    await state_repo.append(
        run_a, "actions", "PolicyGuard", "append", "/actions/policy_decisions",
        {"policy_decisions": [{
            "action_id": "act_pause",
            "action_type": "runtime_tool",
            "risk": "high",
            "decision": "require_approval",
            "status": "pending_approval",
            "reason": "high risk",
            "tool_name": "pause_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        }]},
    )

    # 사용자가 run B의 action에 대해 승인 링크를 승인한 상태.
    approval_repo = get_approval_repo()
    link = approval_repo.create(
        run_b,
        "act_pause",
        {
            "tool_name": "pause_connector",
            "tool_params": {"connector_name": "orders-source-connector"},
        },
    )
    approval_repo.approve(link.approval_id)

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
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

        # run B는 빈 State지만 incident_id로 run A 후보를 복원해야 한다.
        await run_workflow(
            run_id=run_b,
            user_message="승인할게",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
            requested_incident_id=incident_id,
        )

    # cross-turn 복원된 후보로 executor가 실행됨.
    assert len(captured) == 1
    candidate = captured[0]
    assert candidate.action_id == "act_pause"
    assert candidate.tool_name == "pause_connector"
    assert candidate.tool_params == {"connector_name": "orders-source-connector"}
    assert candidate.status == ActionStatus.READY


@pytest.mark.asyncio
async def test_cross_turn_restore_skipped_without_incident_id():
    """incident_id가 없으면 cross-turn 복원을 시도하지 않고 빈 후보로 시작한다."""
    from app.persistence.run_repository import InMemoryRunRepository as _RunRepo

    run_repo = _RunRepo()
    # incident_id 없는 직전 run이 후보를 남겼더라도 키가 없으면 복원 불가.
    await run_repo.create("run_orphan", "incident_analysis", incident_id=None)
    state_repo = InMemoryStateRepository()
    await state_repo.append(
        "run_orphan", "actions", "Remediation", "append", "/actions/candidates",
        {"candidates": [{
            "action_id": "act_x",
            "action_type": "runtime_tool",
            "action_name": "c",
            "risk": "low",
            "reason": "r",
            "tool_name": "get_connector_status",
        }]},
    )

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    registry = AsyncMock()

    seen: list = []

    async def _capture_policy(candidates, **kwargs):
        seen.extend(candidates)
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
            run_id="run_no_incident",
            user_message="재시작해줘",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    # incident_id가 없으므로 다른 run의 후보를 끌어오지 않는다(빈 list).
    assert seen == []


@pytest.mark.asyncio
async def test_action_execution_uses_requested_candidate_for_new_run():
    """Incident Run handoff can execute a selected report candidate in a fresh run."""
    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    run_repo.get.return_value = None
    registry = MagicMock()
    registry.get_definition.return_value = _FakeToolDefinition(
        name="pause_connector",
        risk=RiskLevel.MEDIUM,
    )
    seen: list = []

    async def _capture_policy(candidates, **kwargs):
        seen.extend(candidates)
        return PolicyGuardOutput(policy_decisions=[])

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_policy_guard", new=AsyncMock(side_effect=_capture_policy)),
        patch("app.workflow.runner.run_executor", new_callable=AsyncMock) as mock_exec,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_state_repo", return_value=InMemoryStateRepository()),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = InMemoryEventRepository()
        mock_router.return_value = RouterOutput(route_decision=RouteDecision(
            mode=AgentMode.SIMPLE_QUERY, remediation_requested=False, reason="default", required_flow=[],
        ))
        mock_exec.return_value = ExecutorOutput(execution_results=[])
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "완료"

        await run_workflow(
            run_id="run_fresh_action",
            user_message="run selected action",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
            requested_mode="action_execution",
            requested_incident_id="incident_001",
            requested_action_candidate={
                "action_id": "act_selected",
                "action_type": "runtime_tool",
                "action_name": "pause_connector",
                "risk": "medium",
                "reason": "selected from report",
                "tool_name": "pause_connector",
                "tool_params": {"connector_name": "orders-source-connector"},
            },
        )

    assert len(seen) == 1
    candidate = seen[0]
    assert candidate.action_id == "act_selected"
    assert candidate.tool_name == "pause_connector"
    assert candidate.risk == RiskLevel.HIGH
    assert candidate.tool_params == {"connector_name": "orders-source-connector"}


@pytest.mark.asyncio
async def test_action_execution_waits_for_human_approval_before_executor():
    """High-risk selected actions stop at HITL approval before backend mutation."""
    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    run_repo.get.return_value = None
    registry = MagicMock()
    registry.get_definition.return_value = None

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_policy_guard", new_callable=AsyncMock) as mock_policy,
        patch("app.workflow.runner.run_executor", new_callable=AsyncMock) as mock_exec,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_state_repo", return_value=InMemoryStateRepository()),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = InMemoryEventRepository()
        mock_router.return_value = RouterOutput(route_decision=RouteDecision(
            mode=AgentMode.ACTION_EXECUTION, remediation_requested=False, reason="exec", required_flow=[],
        ))
        mock_policy.return_value = PolicyGuardOutput(policy_decisions=[
            PolicyDecisionOutput(
                action_id="act_needs_approval",
                action_type=ActionType.RUNTIME_TOOL,
                risk=RiskLevel.HIGH,
                decision=PolicyDecisionType.REQUIRE_APPROVAL,
                status=ActionStatus.PENDING_APPROVAL,
                reason="high risk",
                tool_name="pause_connector",
            )
        ])
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "완료"

        await run_workflow(
            run_id="run_wait_for_approval",
            user_message="run selected action",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
            requested_mode="action_execution",
            requested_action_candidate={
                "action_id": "act_needs_approval",
                "action_type": "runtime_tool",
                "action_name": "pause_connector",
                "risk": "high",
                "reason": "selected from report",
                "tool_name": "pause_connector",
                "tool_params": {"connector_name": "orders-source-connector"},
            },
        )

    run_repo.update_status.assert_any_await("run_wait_for_approval", "waiting_for_approval", "approval_gate")
    mock_exec.assert_not_awaited()
    mock_verifier.assert_not_awaited()
    mock_report.assert_not_awaited()
