"""#532 클린/정상 결과(인시던트 0건) no-progress 종결 회귀 테스트.

벤치마크 버그: 정상 데이터 조회가 classifier scope_unclear / verifier gap
loopback으로 같은 결정적 plan을 재수집하다 예산 초과로 "실행 예산 초과" 카드로
실패한다. 수정 후에는 같은 plan을 다시 받으면(no-progress) "인시던트 없음" 답변으로
정상(completed) 종결해야 한다.
"""
from __future__ import annotations

from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.state_repository import InMemoryStateRepository
from app.schemas.events import StreamingEventType
from app.schemas.outputs import (
    Classification,
    ClassifierOutput,
    CorrelationOutput,
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
    VerificationStatus,
)
from app.agents import classifier as classifier_agent
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.runner import _no_incident_answer, run_workflow


# ── 픽스처 빌더 ────────────────────────────────────────────────────────────────

class _CapturingReportRepository:
    def __init__(self) -> None:
        self.created: list[tuple[tuple, dict]] = []

    async def create(self, *args, **kwargs):
        self.created.append((args, kwargs))
        return SimpleNamespace(id="rep_532")


def _router_out() -> RouterOutput:
    return RouterOutput(
        route_decision=RouteDecision(
            mode=AgentMode.INCIDENT_ANALYSIS,
            remediation_requested=False,
            reason="test",
            required_flow=[],
        )
    )


def _correlation_out() -> CorrelationOutput:
    return CorrelationOutput(
        correlation_id="corr_532",
        scope=IncidentScope.SINGLE,
        groups=[],
        related_alert_ids=[],
    )


def _planner_out(plan_hash: str = "plan_hash_clean") -> PlannerOutput:
    return PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id="s1",
                tool_name="get_metrics",
                params={},
                purpose="정상 여부 확인",
                depends_on=[],
                plan_hash=plan_hash,
            )
        ]
    )


def _clean_retrieval_out() -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev_clean",
                type=EvidenceType.TOOL_RESULT,
                store_ref="evidence://run/clean",
                summary="정상 범위 메트릭",
                redaction_status=RedactionStatus.REDACTED,
                collected_by="retrieval",
                collected_at=datetime.now(timezone.utc),
            )
        ]
    )


def _unclear_classifier_out() -> ClassifierOutput:
    """incident 유형을 하나도 확정하지 못한 scope_unclear 상태."""
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[
                IncidentTypeOutput(
                    type=classifier_agent.UNKNOWN_INCIDENT_TYPE,
                    confidence=0.0,
                    evidence_ids=[],
                )
            ],
            needs_incident_group_analysis=False,
        )
    )


def _clear_classifier_out() -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[
                IncidentTypeOutput(
                    type="latency_spike",
                    confidence=0.9,
                    evidence_ids=["ev_clean"],
                )
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
                supporting_evidence_ids=["ev_clean"],
                evidence_gap=[],
                explanation="test root cause",
            )
        ]
    )


def _pass_verifier_out() -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id="v1",
                target="report",
                status=VerificationStatus.PASS,
                approved_for_final_response=True,
                reason="ok",
            )
        ]
    )


# ── 테스트 ─────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_clean_result_completes_via_no_progress_instead_of_budget_failure():
    """(i)·(iii) 같은 plan 재수집 + scope_unclear → completed로 정상 종결한다.

    실패(failed) 상태로 끝나지 않고, '실행 예산 초과' 텍스트도 절대 내보내지 않으며,
    planner는 최대 2회만 호출된다.
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
        patch("app.workflow.runner.run_correlation", new_callable=AsyncMock) as mock_correlation,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.classifier_agent.run_classifier", new_callable=AsyncMock) as mock_classifier,
        patch("app.workflow.runner.rca_agent.run_rca", new_callable=AsyncMock) as mock_rca,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor", return_value=supervisor),
        patch("app.workflow.runner.get_event_repo", return_value=InMemoryEventRepository()),
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_report_repo", return_value=report_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_router.return_value = _router_out()
        mock_correlation.return_value = _correlation_out()
        # 매 호출 같은 plan_hash(결정적 plan 재수집).
        mock_planner.return_value = _planner_out("plan_hash_clean")
        mock_retrieval.return_value = _clean_retrieval_out()
        # scope_unclear → classifier가 Planner로 loopback 등록.
        mock_classifier.return_value = _unclear_classifier_out()
        mock_rca.return_value = _rca_out()
        mock_verifier.return_value = _pass_verifier_out()
        mock_report.return_value = "정상입니다"

        await run_workflow(
            run_id="run_clean_532",
            user_message="현재 시스템 정상인가요?",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    statuses = [call.args for call in run_repo.update_status.await_args_list]
    # completed로 종결, failed는 한 번도 없어야 한다.
    assert ("run_clean_532", "completed", None) in statuses
    assert not any(args[1] == "failed" for args in statuses)

    # no-incident 답변을 담은 RUN_COMPLETED 이벤트가 있어야 한다.
    expected_answer = _no_incident_answer(_clean_retrieval_out())
    run_completed = [
        e for e in published if e.type == StreamingEventType.RUN_COMPLETED
    ]
    assert run_completed
    assert any(e.payload.get("answer") == expected_answer for e in run_completed)

    # '실행 예산 초과' 텍스트는 어떤 이벤트에도 없어야 한다.
    assert not any("실행 예산 초과" in e.message for e in published)
    assert not any("실행 예산 초과" in str(e.payload) for e in published)

    # planner는 최대 2회(최초 + loopback 1회) 호출.
    assert mock_planner.await_count <= 2


@pytest.mark.asyncio
async def test_genuine_new_evidence_does_not_early_exit():
    """(ii) 2번째 plan이 다르면(plan_hash 변경) no-progress로 조기 종료하지 않고
    report까지 정상 진행한다(회귀 안전성)."""
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
        patch("app.workflow.runner.run_correlation", new_callable=AsyncMock) as mock_correlation,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.classifier_agent.run_classifier", new_callable=AsyncMock) as mock_classifier,
        patch("app.workflow.runner.rca_agent.run_rca", new_callable=AsyncMock) as mock_rca,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor", return_value=supervisor),
        patch("app.workflow.runner.get_event_repo", return_value=InMemoryEventRepository()),
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_report_repo", return_value=report_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_router.return_value = _router_out()
        mock_correlation.return_value = _correlation_out()
        # 2번째 호출에서 다른 plan_hash → 진짜 새 수집(진전 있음).
        mock_planner.side_effect = [
            _planner_out("plan_hash_first"),
            _planner_out("plan_hash_second"),
        ]
        mock_retrieval.return_value = _clean_retrieval_out()
        # 1차 classifier는 scope_unclear(→ planner loopback), 2차는 scope 확정.
        mock_classifier.side_effect = [
            _unclear_classifier_out(),
            _clear_classifier_out(),
        ]
        mock_rca.return_value = _rca_out()
        mock_verifier.return_value = _pass_verifier_out()
        mock_report.return_value = "report 정상 분석 결과"

        await run_workflow(
            run_id="run_progress_532",
            user_message="lag 추이 확인",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    statuses = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_progress_532", "completed", None) in statuses
    assert not any(args[1] == "failed" for args in statuses)
    # 새 plan으로 loopback 후 report까지 진행 → planner 2회.
    assert mock_planner.await_count == 2
    # 조기 종료가 아니라 report 답변으로 종결.
    run_completed = [e for e in published if e.type == StreamingEventType.RUN_COMPLETED]
    assert run_completed
    final_answer = run_completed[-1].payload.get("answer")
    assert final_answer == "report 정상 분석 결과"
    # no-incident 조기 종료 메시지가 아니어야 한다.
    assert "이상 징후가 발견되지 않았습니다" not in final_answer
    assert not any("실행 예산 초과" in e.message for e in published)


def test_no_incident_answer_variants():
    """(iv) _no_incident_answer: None / 빈 / 비어있지 않은 근거(건수) 분기."""
    assert _no_incident_answer(None) == (
        "조회한 데이터에서 인시던트나 이상 징후가 발견되지 않았습니다. (정상)"
    )
    assert _no_incident_answer(RetrievalOutput(evidence_items=[])) == (
        "조회한 데이터에서 인시던트나 이상 징후가 발견되지 않았습니다. (정상)"
    )
    non_empty = _no_incident_answer(_clean_retrieval_out())
    assert "수집 근거 1건 검토 결과 정상" in non_empty
