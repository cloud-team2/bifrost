"""#453 Verifier loopback (fail/needs_revision) 회귀 테스트.

Verifier 차단기가 no-op이던 문제 — fail/needs_revision 결과가 책임 Agent로
되돌아가지 않고 무조건 report로 진행하던 것 — 를 검증한다(§9 Verifier Loop,
§5.1 루프 가드).
"""
from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

import pytest

from app.schemas.outputs import (
    Classification,
    ClassifierOutput,
    IncidentTypeOutput,
    PlannerOutput,
    RcaOutput,
    RetrievalOutput,
    RetrievalPlanStep,
    RouteDecision,
    RouterOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.schemas.state import (
    AgentMode,
    EvidenceItem,
    EvidenceType,
    IncidentScope,
    RedactionStatus,
    RootCauseCandidate,
    RunStatus,
    VerificationStatus,
)
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.guards import RunBudgetExceeded
from app.workflow.runner import run_workflow


def _sup(**policy_kwargs) -> tuple[Supervisor, InMemoryStateStore]:
    store = InMemoryStateStore()
    return Supervisor(store=store, policy=RetryPolicy(**policy_kwargs)), store


def _drive_to(sup: Supervisor, run_id: str, target: str, cap: int = 50) -> None:
    """target stage에 도달할 때까지 advance한다(무한루프 방지 cap 포함)."""
    for _ in range(cap):
        if sup.advance(run_id) == target:
            return
    raise AssertionError(f"{target} stage에 도달하지 못함 (무한루프 의심)")


# ── Supervisor 단위 ────────────────────────────────────────────────────────────


def test_pass_proceeds_to_report() -> None:
    sup, store = _sup()
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS)
    _drive_to(sup, "r", "verifier")

    sup.record_verifier_result("r", VerificationStatus.PASS, None)

    assert sup.advance("r") == "report"
    assert sup.advance("r") is None
    assert store.get("r").run.status == RunStatus.COMPLETED
    assert store.get("r").run.guards.fail_loops == 0


def test_fail_loops_back_then_terminates() -> None:
    sup, store = _sup(max_fail_loops=1)
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS)
    _drive_to(sup, "r", "verifier")

    # 1차 fail → planner로 loopback(report 아님).
    sup.record_verifier_result("r", VerificationStatus.FAIL, "planner")
    assert sup.advance("r") == "planner"
    assert store.get("r").run.guards.fail_loops == 1

    # loopback 경로를 다시 verifier까지 진행.
    _drive_to(sup, "r", "verifier")

    # 2차 fail → 예산 소진 → run failed, report 진입 금지.
    sup.record_verifier_result("r", VerificationStatus.FAIL, "planner")
    with pytest.raises(RunBudgetExceeded) as exc:
        sup.advance("r")
    assert exc.value.reason == "fail_loops"
    assert store.get("r").run.status == RunStatus.FAILED


def test_needs_revision_loops_back_within_gap_budget() -> None:
    sup, store = _sup(max_gap_loops=2)
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS)
    _drive_to(sup, "r", "verifier")

    # gap_loops=2 → 두 번의 loopback 허용, 세 번째에 종료.
    for expected_count in (1, 2):
        sup.record_verifier_result("r", VerificationStatus.NEEDS_REVISION, "planner")
        assert sup.advance("r") == "planner"
        assert store.get("r").run.guards.gap_loops == expected_count
        _drive_to(sup, "r", "verifier")

    sup.record_verifier_result("r", VerificationStatus.NEEDS_REVISION, "planner")
    with pytest.raises(RunBudgetExceeded) as exc:
        sup.advance("r")
    assert exc.value.reason == "gap_loops"
    assert store.get("r").run.status == RunStatus.FAILED


def test_loopback_target_rca_is_honored() -> None:
    sup, _ = _sup(max_fail_loops=1)
    sup.start_run("r", AgentMode.INCIDENT_ANALYSIS)
    _drive_to(sup, "r", "verifier")

    sup.record_verifier_result("r", VerificationStatus.NEEDS_REVISION, "rca")
    assert sup.advance("r") == "rca"  # 정적 테이블의 report 대신 rca로 되돌아감.


# ── Runner 통합 ─────────────────────────────────────────────────────────────────


def _router_out() -> RouterOutput:
    return RouterOutput(
        route_decision=RouteDecision(
            mode=AgentMode.INCIDENT_ANALYSIS,
            remediation_requested=False,
            reason="test",
            required_flow=[],
        )
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


def _classifier_out() -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[
                IncidentTypeOutput(type="latency_spike", confidence=0.9, evidence_ids=["ev1"])
            ],
            needs_incident_group_analysis=False,
        )
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


def _fail_verifier_out() -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id="v1",
                target="root_cause",
                status=VerificationStatus.FAIL,
                approved_for_final_response=False,
                reason="검증 실패",
                next_agent="planner",
            )
        ]
    )


@pytest.mark.asyncio
async def test_runner_blocks_report_on_verifier_fail() -> None:
    """Verifier가 계속 fail이면 report를 내보내지 않고 run failed로 종료한다."""
    from app.persistence.event_repository import InMemoryEventRepository
    from app.persistence.state_repository import InMemoryStateRepository

    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()

    supervisor = Supervisor(
        store=InMemoryStateStore(),
        policy=RetryPolicy(max_fail_loops=1),
    )

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
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
        # 매 loopback마다 새 plan(다른 plan_hash) → #532 no-progress 조기 종료가
        # 아니라 실제 재수집이 일어나지만, verifier가 끝내 fail이라 fail_loops 예산으로
        # failed 종료되는 경로를 검증한다.
        mock_planner.side_effect = [_planner_out("h1"), _planner_out("h2"), _planner_out("h3")]
        mock_retrieval.return_value = _retrieval_out()
        mock_classifier.return_value = _classifier_out()
        mock_rca.return_value = _rca_out()
        mock_verifier.return_value = _fail_verifier_out()
        mock_report.return_value = "검증된 답변"

        await run_workflow(
            run_id="run_fail",
            user_message="서버 장애가 발생했습니다",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    # report stage에 진입하지 않아야 한다(검증 안 된 답변 차단).
    mock_report.assert_not_awaited()
    # verifier는 최초 + loopback 1회 = 2번 실행.
    assert mock_verifier.await_count == 2
    # run은 failed로 종료.
    status_updates = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_fail", "failed", None) in status_updates
