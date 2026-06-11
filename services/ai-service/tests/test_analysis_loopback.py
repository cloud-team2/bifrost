"""#476 분석 루프 배선 회귀 테스트.

설계(contract-workflow-control.md §3 flowchart)의 두 순환을 검증한다:
  - Classifier scope_unclear → Planner 재수집(scope_loops 예산).
  - Policy Guard revise_action → Remediation 재생성(revise_action_loops 예산).

#453의 Verifier loopback 인프라(_pending_loopback / _force_fail)를 재사용하며,
카운터 상한으로 유한 종료(무한루프 방지)됨을 보장한다.
"""
from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

import pytest

from app.schemas.outputs import (
    Classification,
    ClassifierOutput,
    CorrelationOutput,
    IncidentTypeOutput,
    PlannerOutput,
    PolicyDecisionOutput,
    PolicyGuardOutput,
    RcaOutput,
    RemediationOutput,
    RetrievalOutput,
    RetrievalPlanStep,
    RouteDecision,
    RouterOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.schemas.state import (
    ActionStatus,
    ActionType,
    AgentMode,
    EvidenceItem,
    EvidenceType,
    IncidentScope,
    PolicyDecisionType,
    RedactionStatus,
    RiskLevel,
    RootCauseCandidate,
    RunStatus,
    VerificationStatus,
)
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.guards import RunBudgetExceeded
from app.workflow.runner import (
    _classifier_scope_unclear,
    _policy_revise_action,
    run_workflow,
)


UNKNOWN = "UNKNOWN_NEEDS_MORE_EVIDENCE"


def _sup(**policy_kwargs) -> tuple[Supervisor, InMemoryStateStore]:
    store = InMemoryStateStore()
    return Supervisor(store=store, policy=RetryPolicy(**policy_kwargs)), store


def _drive_to(sup: Supervisor, run_id: str, target: str, cap: int = 60) -> None:
    for _ in range(cap):
        if sup.advance(run_id) == target:
            return
    raise AssertionError(f"{target} stage에 도달하지 못함 (무한루프 의심)")


# ── 신호 매핑 단위 ───────────────────────────────────────────────────────────────


def _clf(types: list[str]) -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[
                IncidentTypeOutput(type=t, confidence=0.5, evidence_ids=[]) for t in types
            ],
            needs_incident_group_analysis=False,
        )
    )


def test_scope_unclear_signal_mapping() -> None:
    # 알려진 type 미확정(UNKNOWN만) → scope_unclear.
    assert _classifier_scope_unclear(_clf([UNKNOWN])) is True
    assert _classifier_scope_unclear(_clf([])) is True
    # 명확한 type 존재 → scope clear.
    assert _classifier_scope_unclear(_clf(["latency_spike"])) is False
    assert _classifier_scope_unclear(_clf(["latency_spike", UNKNOWN])) is False
    assert _classifier_scope_unclear(None) is False


def _policy(decision: PolicyDecisionType, status: ActionStatus) -> PolicyGuardOutput:
    return PolicyGuardOutput(
        policy_decisions=[
            PolicyDecisionOutput(
                action_id="a1",
                action_type=ActionType.NOTIFICATION,
                risk=RiskLevel.LOW,
                decision=decision,
                status=status,
                reason="test",
            )
        ]
    )


def test_revise_action_signal_mapping() -> None:
    assert _policy_revise_action(_policy(PolicyDecisionType.DENY, ActionStatus.BLOCKED)) is True
    assert _policy_revise_action(_policy(PolicyDecisionType.ALLOW, ActionStatus.READY)) is False
    assert _policy_revise_action(PolicyGuardOutput(policy_decisions=[])) is False
    assert _policy_revise_action(None) is False


# ── Supervisor 단위: Classifier scope_unclear → Planner ──────────────────────────


def test_scope_clear_proceeds_to_rca() -> None:
    sup, store = _sup()
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS)
    _drive_to(sup, "r", "classifier")

    sup.record_classifier_result("r", scope_unclear=False)

    assert sup.advance("r") == "rca"  # 정적 테이블대로 진행, loopback 없음.
    assert store.get("r").run.guards.scope_loops == 0


def test_scope_unclear_loops_to_planner_then_terminates() -> None:
    sup, store = _sup(max_scope_loops=2)
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS)

    # scope_loops=2 → 두 번의 Planner loopback 허용, 세 번째에 종료.
    for expected_count in (1, 2):
        _drive_to(sup, "r", "classifier")
        sup.record_classifier_result("r", scope_unclear=True)
        assert sup.advance("r") == "planner"  # 정적 테이블의 rca 대신 planner.
        assert store.get("r").run.guards.scope_loops == expected_count

    _drive_to(sup, "r", "classifier")
    sup.record_classifier_result("r", scope_unclear=True)
    with pytest.raises(RunBudgetExceeded) as exc:
        sup.advance("r")
    assert exc.value.reason == "scope_loops"
    assert store.get("r").run.status == RunStatus.FAILED


# ── Supervisor 단위: Policy Guard revise_action → Remediation ─────────────────────


def test_normal_decision_proceeds_to_verifier() -> None:
    sup, store = _sup()
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS, remediation_requested=True)
    _drive_to(sup, "r", "policy_guard")

    sup.record_policy_guard_result("r", revise_action=False)

    assert sup.advance("r") == "verifier"  # 정적 테이블대로 진행, loopback 없음.
    assert store.get("r").run.guards.revise_action_loops == 0


def test_revise_action_loops_to_remediation_then_terminates() -> None:
    sup, store = _sup(max_revise_action_loops=2)
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS, remediation_requested=True)

    for expected_count in (1, 2):
        _drive_to(sup, "r", "policy_guard")
        sup.record_policy_guard_result("r", revise_action=True)
        assert sup.advance("r") == "remediation"  # 정적 테이블의 verifier 대신 remediation.
        assert store.get("r").run.guards.revise_action_loops == expected_count

    _drive_to(sup, "r", "policy_guard")
    sup.record_policy_guard_result("r", revise_action=True)
    with pytest.raises(RunBudgetExceeded) as exc:
        sup.advance("r")
    assert exc.value.reason == "revise_action_loops"
    assert store.get("r").run.status == RunStatus.FAILED


def test_loopbacks_do_not_interfere_with_verifier() -> None:
    """scope/revise_action loopback 인프라가 기존 verifier loopback과 공존한다."""
    sup, store = _sup()
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS)
    _drive_to(sup, "r", "classifier")
    sup.record_classifier_result("r", scope_unclear=False)
    _drive_to(sup, "r", "verifier")
    sup.record_verifier_result("r", VerificationStatus.PASS, None)
    assert sup.advance("r") == "report"
    assert sup.advance("r") is None
    assert store.get("r").run.status == RunStatus.COMPLETED


# ── Runner 통합 ─────────────────────────────────────────────────────────────────


def _router_out(remediation: bool = False) -> RouterOutput:
    return RouterOutput(
        route_decision=RouteDecision(
            mode=AgentMode.INCIDENT_ANALYSIS,
            remediation_requested=remediation,
            reason="test",
            required_flow=[],
        )
    )


def _correlation_out() -> CorrelationOutput:
    return CorrelationOutput(
        correlation_id="corr1",
        scope=IncidentScope.SINGLE,
        groups=[],
        related_alert_ids=[],
    )


def _planner_out(plan_hash: str = "h1") -> PlannerOutput:
    return PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id="s1",
                tool_name="get_metrics",
                params={},
                purpose="test",
                depends_on=[],
                plan_hash=plan_hash,
            )
        ]
    )


def _retrieval_out() -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev1",
                type=EvidenceType.TOOL_RESULT,
                store_ref="evidence://run/s1",
                summary="redacted summary",
                redaction_status=RedactionStatus.REDACTED,
                collected_by="retrieval",
                collected_at=datetime.now(timezone.utc),
            )
        ]
    )


def _rca_out() -> RcaOutput:
    return RcaOutput(
        root_cause_candidates=[
            RootCauseCandidate(
                root_cause_id="rc_001",
                confidence=0.8,
                required_evidence_satisfied=True,
                supporting_evidence_ids=["ev1"],
                evidence_gap=[],
                explanation="test",
            )
        ]
    )


def _remediation_out() -> RemediationOutput:
    return RemediationOutput(action_candidates=[])


def _pass_verifier_out() -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id="v1",
                target="root_cause",
                status=VerificationStatus.PASS,
                approved_for_final_response=True,
                reason="ok",
                next_agent=None,
            )
        ]
    )


def _deny_policy_out() -> PolicyGuardOutput:
    return _policy(PolicyDecisionType.DENY, ActionStatus.BLOCKED)


@pytest.mark.asyncio
async def test_runner_scope_unclear_loops_then_fails() -> None:
    """Classifier가 계속 scope_unclear면 RCA로 못 가고 scope_loops 소진 후 종료."""
    from app.persistence.event_repository import InMemoryEventRepository
    from app.persistence.state_repository import InMemoryStateRepository

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()

    supervisor = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy(max_scope_loops=1))

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_correlation", new_callable=AsyncMock) as mock_corr,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.classifier_agent.run_classifier", new_callable=AsyncMock) as mock_classifier,
        patch("app.workflow.runner.rca_agent.run_rca", new_callable=AsyncMock) as mock_rca,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_event_repo,
        patch("app.workflow.runner.get_state_repo") as mock_get_state_repo,
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = supervisor
        mock_get_event_repo.return_value = InMemoryEventRepository()
        mock_get_state_repo.return_value = InMemoryStateRepository()
        mock_router.return_value = _router_out()
        mock_corr.return_value = _correlation_out()
        # 매 loopback마다 새 plan(다른 plan_hash) → #532 no-progress 조기 종료가
        # 아니라 실제 재수집이 일어나지만, scope가 끝내 불명확해 scope_loops 예산으로
        # failed 종료되는 경로를 검증한다.
        mock_planner.side_effect = [_planner_out("h1"), _planner_out("h2"), _planner_out("h3")]
        mock_retrieval.return_value = _retrieval_out()
        # 항상 scope_unclear(UNKNOWN만) → Planner loopback 유발.
        mock_classifier.return_value = _clf([UNKNOWN])
        mock_rca.return_value = _rca_out()
        mock_verifier.return_value = _pass_verifier_out()
        mock_report.return_value = "답변"

        await run_workflow(
            run_id="run_scope",
            user_message="알 수 없는 장애",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    # scope가 끝내 불명확하므로 RCA/Report에 도달하지 않고 실패로 종료.
    mock_rca.assert_not_awaited()
    mock_report.assert_not_awaited()
    # 최초 + loopback 1회 = classifier 2회.
    assert mock_classifier.await_count == 2
    status_updates = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_scope", "failed", None) in status_updates


@pytest.mark.asyncio
async def test_runner_revise_action_loops_then_fails() -> None:
    """Policy Guard가 계속 DENY면 Remediation을 반복 재생성하다 예산 소진 후 종료."""
    from app.persistence.event_repository import InMemoryEventRepository
    from app.persistence.state_repository import InMemoryStateRepository

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()

    supervisor = Supervisor(
        store=InMemoryStateStore(), policy=RetryPolicy(max_revise_action_loops=1)
    )

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.run_correlation", new_callable=AsyncMock) as mock_corr,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.classifier_agent.run_classifier", new_callable=AsyncMock) as mock_classifier,
        patch("app.workflow.runner.rca_agent.run_rca", new_callable=AsyncMock) as mock_rca,
        patch("app.workflow.runner.remediation_agent.run_remediation", new_callable=AsyncMock) as mock_rem,
        patch("app.workflow.runner.run_policy_guard", new_callable=AsyncMock) as mock_policy,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_event_repo,
        patch("app.workflow.runner.get_state_repo") as mock_get_state_repo,
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = supervisor
        mock_get_event_repo.return_value = InMemoryEventRepository()
        mock_get_state_repo.return_value = InMemoryStateRepository()
        mock_router.return_value = _router_out(remediation=True)
        mock_corr.return_value = _correlation_out()
        mock_planner.return_value = _planner_out()
        mock_retrieval.return_value = _retrieval_out()
        mock_classifier.return_value = _clf(["latency_spike"])  # scope 명확.
        mock_rca.return_value = _rca_out()
        mock_rem.return_value = _remediation_out()
        mock_policy.return_value = _deny_policy_out()  # 항상 DENY → revise_action.
        mock_verifier.return_value = _pass_verifier_out()
        mock_report.return_value = "답변"

        await run_workflow(
            run_id="run_revise",
            user_message="조치가 필요한 장애",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    # 조치가 끝내 정책 불가이므로 Verifier/Report에 도달하지 않고 실패로 종료.
    mock_verifier.assert_not_awaited()
    mock_report.assert_not_awaited()
    # 최초 + loopback 1회 = remediation/policy 각 2회.
    assert mock_rem.await_count == 2
    assert mock_policy.await_count == 2
    status_updates = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_revise", "failed", None) in status_updates
