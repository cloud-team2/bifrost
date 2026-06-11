"""#553 실행할 조치가 없을 때 graceful 종결 회귀 테스트.

벤치마크 버그: action_execution/approval_decision에서 실행할 승인된 조치가 하나도
없으면(ready_candidates 비어 있음) Executor가 빈 결과만 내고, Verifier가
needs_revision으로 Executor에 loopback시켜 gap_loops 예산 초과("실행 예산 초과")로
run이 실패한다. 결정적 Executor를 같은 빈 후보로 재실행해봐야 진전이 없으므로,
수정 후에는 추가 LLM 호출 없이 "실행할 조치 없음" 답변으로 정상(completed) 종결해야
한다.
"""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.state_repository import InMemoryStateRepository
from app.schemas.events import StreamingEventType
from app.schemas.outputs import (
    ApprovalGateOutput,
    ChangeManagementOutput,
    ChangeManagementRecordOutput,
    ExecutionResultOutput,
    ExecutorOutput,
    PolicyDecisionOutput,
    PolicyGuardOutput,
    RouteDecision,
    RouterOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.persistence.change_ticket_repository import STATUS_VERIFIED
from app.schemas.state import (
    ActionStatus,
    ActionType,
    AgentMode,
    PolicyDecisionType,
    RiskLevel,
    VerificationStatus,
)
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.streaming.event_bus import EventBus
from app.workflow.runner import _no_action_answer, run_workflow


# ── 픽스처 빌더 ────────────────────────────────────────────────────────────────

class _CapturingReportRepository:
    def __init__(self) -> None:
        self.created: list[tuple[tuple, dict]] = []

    async def create(self, *args, **kwargs):
        self.created.append((args, kwargs))
        return SimpleNamespace(id="rep_553")


def _router_out(mode: AgentMode) -> RouterOutput:
    return RouterOutput(
        route_decision=RouteDecision(
            mode=mode,
            remediation_requested=False,
            reason="test",
            required_flow=[],
        )
    )


def _verified_policy_guard_out() -> PolicyGuardOutput:
    """변경관리로 verified 처리되어 executor-ready로 풀리는 조치 1건."""
    return PolicyGuardOutput(
        policy_decisions=[
            PolicyDecisionOutput(
                action_id="act_ready_553",
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


def _pass_verifier_out() -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id="v_553",
                target="execution_result",
                status=VerificationStatus.PASS,
                approved_for_final_response=True,
                reason="ok",
            )
        ]
    )


def _completed_executor_out() -> ExecutorOutput:
    return ExecutorOutput(
        execution_results=[
            ExecutionResultOutput(
                action_id="act_ready_553",
                tool_name="pause_connector",
                status=ActionStatus.COMPLETED,
                after_evidence_id="ev_after_553",
                summary="connector paused",
            )
        ]
    )


# ── 테스트 ─────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_action_execution_no_ready_candidates_completes_gracefully():
    """(i) action_execution + ready_candidates 0건 → completed로 정상 종결.

    failed/예산 초과 없이, _no_action_answer를 담은 RUN_COMPLETED로 끝나야 하고,
    Executor는 호출되되 Verifier 루프에는 진입하지 않아야 한다(gap_loops 미소진).
    """
    state_repo = InMemoryStateRepository()
    report_repo = _CapturingReportRepository()
    supervisor = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
    bus = EventBus()
    published: list = []
    bus.publish = AsyncMock(side_effect=lambda run_id, event: published.append(event))  # type: ignore[method-assign]
    run_repo = AsyncMock()
    run_repo.get.return_value = None

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_policy_guard", new_callable=AsyncMock) as mock_policy_guard,
        patch("app.workflow.runner.run_approval_gate", new_callable=AsyncMock) as mock_approval_gate,
        patch("app.workflow.runner.run_change_gate", new_callable=AsyncMock) as mock_change_gate,
        patch("app.workflow.runner.run_executor", new_callable=AsyncMock) as mock_executor,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.get_supervisor", return_value=supervisor),
        patch("app.workflow.runner.get_event_repo", return_value=InMemoryEventRepository()),
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_report_repo", return_value=report_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_router.return_value = _router_out(AgentMode.ACTION_EXECUTION)
        # 승인/검증된 조치가 하나도 없는 빈 게이트 결과.
        mock_policy_guard.return_value = PolicyGuardOutput(policy_decisions=[])
        mock_approval_gate.return_value = ApprovalGateOutput(approved_actions=[], run_status="running")
        mock_change_gate.return_value = ChangeManagementOutput(
            change_management_records=[], run_status="running"
        )
        mock_executor.return_value = ExecutorOutput(execution_results=[])

        await run_workflow(
            run_id="run_no_action_553",
            user_message="조치 실행해줘",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    statuses = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_no_action_553", "completed", None) in statuses
    assert not any(args[1] == "failed" for args in statuses)

    expected_answer = _no_action_answer(AgentMode.ACTION_EXECUTION)
    run_completed = [e for e in published if e.type == StreamingEventType.RUN_COMPLETED]
    assert run_completed
    assert any(e.payload.get("answer") == expected_answer for e in run_completed)

    # '실행 예산 초과' 텍스트는 어떤 이벤트에도 없어야 한다.
    assert not any("실행 예산 초과" in e.message for e in published)
    assert not any("실행 예산 초과" in str(e.payload) for e in published)

    # Executor는 호출되지만(빈 후보), Verifier 루프에는 진입하지 않는다.
    mock_executor.assert_awaited_once()
    mock_verifier.assert_not_awaited()


@pytest.mark.asyncio
async def test_approval_decision_no_approved_actions_completes_gracefully():
    """(ii) approval_decision + 승인된 조치 0건 → 동일하게 graceful 종결."""
    state_repo = InMemoryStateRepository()
    report_repo = _CapturingReportRepository()
    supervisor = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
    bus = EventBus()
    published: list = []
    bus.publish = AsyncMock(side_effect=lambda run_id, event: published.append(event))  # type: ignore[method-assign]
    run_repo = AsyncMock()
    run_repo.get.return_value = None

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_approval_gate", new_callable=AsyncMock) as mock_approval_gate,
        patch("app.workflow.runner.run_executor", new_callable=AsyncMock) as mock_executor,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.get_supervisor", return_value=supervisor),
        patch("app.workflow.runner.get_event_repo", return_value=InMemoryEventRepository()),
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_report_repo", return_value=report_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_router.return_value = _router_out(AgentMode.APPROVAL_DECISION)
        mock_approval_gate.return_value = ApprovalGateOutput(approved_actions=[], run_status="running")
        mock_executor.return_value = ExecutorOutput(execution_results=[])

        await run_workflow(
            run_id="run_no_approval_553",
            user_message="승인된 조치 실행",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    statuses = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_no_approval_553", "completed", None) in statuses
    assert not any(args[1] == "failed" for args in statuses)

    expected_answer = _no_action_answer(AgentMode.APPROVAL_DECISION)
    run_completed = [e for e in published if e.type == StreamingEventType.RUN_COMPLETED]
    assert run_completed
    assert any(e.payload.get("answer") == expected_answer for e in run_completed)

    assert not any("실행 예산 초과" in e.message for e in published)
    assert not any("실행 예산 초과" in str(e.payload) for e in published)

    mock_executor.assert_awaited_once()
    mock_verifier.assert_not_awaited()


@pytest.mark.asyncio
async def test_action_execution_with_ready_candidate_goes_through_verifier():
    """(iii) 회귀: 실행 가능한 조치 1건이 정상 실행되면 short-circuit가 아니라
    Verifier를 거쳐 정상 종결한다(short-circuit는 빈 후보에서만 발동)."""
    state_repo = InMemoryStateRepository()
    report_repo = _CapturingReportRepository()
    supervisor = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
    bus = EventBus()
    published: list = []
    bus.publish = AsyncMock(side_effect=lambda run_id, event: published.append(event))  # type: ignore[method-assign]
    run_repo = AsyncMock()
    run_repo.get.return_value = None
    captured_candidates: list = []

    async def _capture_executor(candidates, **kwargs):
        captured_candidates.extend(candidates)
        return _completed_executor_out()

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_policy_guard", new_callable=AsyncMock) as mock_policy_guard,
        patch("app.workflow.runner.run_approval_gate", new_callable=AsyncMock) as mock_approval_gate,
        patch("app.workflow.runner.run_change_gate", new_callable=AsyncMock) as mock_change_gate,
        patch("app.workflow.runner.run_executor", new=AsyncMock(side_effect=_capture_executor)) as mock_executor,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.get_supervisor", return_value=supervisor),
        patch("app.workflow.runner.get_event_repo", return_value=InMemoryEventRepository()),
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_report_repo", return_value=report_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_router.return_value = _router_out(AgentMode.ACTION_EXECUTION)
        mock_policy_guard.return_value = _verified_policy_guard_out()
        mock_approval_gate.return_value = ApprovalGateOutput(approved_actions=[], run_status="running")
        mock_change_gate.return_value = ChangeManagementOutput(
            change_management_records=[
                ChangeManagementRecordOutput(
                    change_ticket_id="CHG-553",
                    action_id="act_ready_553",
                    status=STATUS_VERIFIED,
                )
            ],
            run_status="running",
        )
        mock_verifier.return_value = _pass_verifier_out()

        await run_workflow(
            run_id="run_ready_553",
            user_message="조치 실행",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    statuses = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_ready_553", "completed", None) in statuses
    assert not any(args[1] == "failed" for args in statuses)

    # 실행 가능한 후보가 executor로 전달되고, short-circuit가 아니라 verifier를 거친다.
    mock_executor.assert_awaited_once()
    assert len(captured_candidates) == 1
    assert captured_candidates[0].action_id == "act_ready_553"
    mock_verifier.assert_awaited()

    # 최종 답변은 no-action 답변이 아니어야 한다(정상 실행 경로).
    run_completed = [e for e in published if e.type == StreamingEventType.RUN_COMPLETED]
    assert run_completed
    final_answer = run_completed[-1].payload.get("answer")
    assert final_answer != _no_action_answer(AgentMode.ACTION_EXECUTION)


def test_no_action_answer_variants():
    """(iv) _no_action_answer: mode별 결정적 문구."""
    assert _no_action_answer(AgentMode.ACTION_EXECUTION) == (
        "요청하신 작업에 대해 실행할 승인된 조치가 없습니다."
    )
    assert _no_action_answer(AgentMode.APPROVAL_DECISION) == (
        "승인 대기 중인 조치가 없습니다."
    )
