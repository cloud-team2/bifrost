"""run_workflow() state_patch persistence tests."""
from __future__ import annotations

from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.state_repository import InMemoryStateRepository
from app.schemas.outputs import (
    ActionCandidateOutput,
    Classification,
    ClassifierOutput,
    IncidentTypeOutput,
    PlannerOutput,
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
    ActionType,
    AgentMode,
    EvidenceItem,
    EvidenceType,
    ExecutionDepth,
    IncidentScope,
    RedactionStatus,
    RiskLevel,
    RootCauseCandidate,
    VerificationStatus,
)
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.runner import run_workflow


class _FixedSupervisor:
    def __init__(self, stages: list[str]) -> None:
        self._stages = stages
        self._index = 0

    def start_run(self, *args, **kwargs) -> None:
        return None

    def advance(self, run_id: str) -> str | None:
        if self._index >= len(self._stages):
            return None
        stage = self._stages[self._index]
        self._index += 1
        return stage


class _RecordingSupervisor(_FixedSupervisor):
    def __init__(self, stages: list[str]) -> None:
        super().__init__(stages)
        self.start_args = None
        self.start_kwargs = None

    def start_run(self, *args, **kwargs) -> None:
        self.start_args = args
        self.start_kwargs = kwargs


class _CapturingReportRepository:
    def __init__(self) -> None:
        self.created: list[tuple[tuple, dict]] = []

    async def create(self, *args, **kwargs):
        self.created.append((args, kwargs))
        return SimpleNamespace(id="rep_001")


def _router_out(
    mode: AgentMode = AgentMode.SIMPLE_QUERY,
    remediation_requested: bool = False,
) -> RouterOutput:
    return RouterOutput(
        route_decision=RouteDecision(
            mode=mode,
            remediation_requested=remediation_requested,
            reason="test",
            required_flow=[],
        )
    )


def _planner_out() -> PlannerOutput:
    return PlannerOutput(
        retrieval_plan=[
            RetrievalPlanStep(
                step_id="s1",
                tool_name="get_metrics",
                params={},
                purpose="test",
                depends_on=[],
                plan_hash="plan_hash_001",
            )
        ]
    )


def _retrieval_out(summary: str = "redacted metric summary") -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev1",
                type=EvidenceType.TOOL_RESULT,
                store_ref="evidence://run/s1",
                summary=summary,
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
                IncidentTypeOutput(
                    type="latency_spike",
                    confidence=0.91,
                    evidence_ids=["ev1"],
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
                supporting_evidence_ids=["ev1"],
                evidence_gap=[],
                explanation="test root cause",
            )
        ]
    )


def _remediation_out() -> RemediationOutput:
    return RemediationOutput(
        action_candidates=[
            ActionCandidateOutput(
                action_id="act_001",
                action_type=ActionType.NOTIFICATION,
                action_name="notify_oncall",
                root_cause_id="rc_001",
                risk=RiskLevel.LOW,
                reason="test",
                expected_effect="operator notified",
            )
        ]
    )


def _verifier_out() -> VerifierOutput:
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


async def _run_with_state_repo(
    *,
    run_id: str,
    state_repo,
    mode: AgentMode = AgentMode.SIMPLE_QUERY,
    remediation_requested: bool = False,
    supervisor=None,
    retrieval_summary: str = "redacted metric summary",
) -> InMemoryStateRepository:
    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.classifier_agent.run_classifier", new_callable=AsyncMock) as mock_classifier,
        patch("app.workflow.runner.rca_agent.run_rca", new_callable=AsyncMock) as mock_rca,
        patch("app.workflow.runner.remediation_agent.run_remediation", new_callable=AsyncMock) as mock_remediation,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_event_repo,
        patch("app.workflow.runner.get_state_repo") as mock_get_state_repo,
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = supervisor or Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_event_repo.return_value = InMemoryEventRepository()
        mock_get_state_repo.return_value = state_repo
        mock_router.return_value = _router_out(mode, remediation_requested)
        mock_planner.return_value = _planner_out()
        mock_retrieval.return_value = _retrieval_out(retrieval_summary)
        mock_classifier.return_value = _classifier_out()
        mock_rca.return_value = _rca_out()
        mock_remediation.return_value = _remediation_out()
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "분석 완료"

        await run_workflow(
            run_id=run_id,
            user_message="서버 장애가 발생했습니다",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    return state_repo


@pytest.mark.asyncio
async def test_simple_query_emits_run_and_report_patches() -> None:
    repo = await _run_with_state_repo(run_id="run_simple_patch", state_repo=InMemoryStateRepository())

    patches = await repo.get_patches("run_simple_patch")

    pairs = {(p.namespace, p.author) for p in patches}
    assert len(patches) >= 3
    assert ("run.plan", "Planner") in pairs
    assert ("evidence", "Retrieval") in pairs
    assert ("report", "Report") in pairs
    # #882 단순 조회(기본 bounded_lookup)는 검증 agent 를 호출하지 않는다.
    assert ("verification", "Verifier") not in pairs


@pytest.mark.asyncio
async def test_incident_analysis_emits_full_chain_patches() -> None:
    repo = await _run_with_state_repo(
        run_id="run_incident_patch",
        state_repo=InMemoryStateRepository(),
        mode=AgentMode.INCIDENT_ANALYSIS,
        remediation_requested=True,
        supervisor=_FixedSupervisor([
            "correlation",
            "planner",
            "retrieval",
            "classifier",
            "rca",
            "remediation",
            "policy_guard",
            "approval_gate",
            "change_gate",
            "executor",
            "verifier",
            "report",
        ]),
    )

    patches = await repo.get_patches("run_incident_patch")
    emitted = {(p.namespace, p.author, p.path) for p in patches}

    assert len(patches) in {14, 15}
    assert ("correlation", "CorrelationEngine", "/correlation") in emitted
    assert ("run.plan", "Planner", "/run/plan/executed_plan_hashes") in emitted
    assert ("evidence", "Retrieval", "/evidence/items") in emitted
    assert ("analysis", "Classifier", "/analysis/incident_types") in emitted
    assert ("incident", "Classifier", "/incident/scope") in emitted
    assert ("analysis", "RCA", "/analysis/root_cause_candidates") in emitted
    assert ("actions", "Remediation", "/actions/candidates") in emitted
    assert ("actions", "PolicyGuard", "/actions/policy_decisions") in emitted
    assert ("actions", "PolicyGuard", "/actions/approval_requests") in emitted
    assert ("actions", "HumanApprovalGate", "/actions/approved_actions") in emitted
    assert ("actions", "ChangeManagementGate", "/actions/change_management_records") in emitted
    assert ("actions", "Executor", "/actions/execution_results") in emitted
    assert ("verification", "Verifier", "/verification/verification_results") in emitted
    assert ("report", "Report", "/report/draft") in emitted


@pytest.mark.asyncio
async def test_evidence_patch_has_no_raw_content() -> None:
    repo = await _run_with_state_repo(
        run_id="run_evidence_patch",
        state_repo=InMemoryStateRepository(),
        retrieval_summary="safe redacted summary",
    )

    evidence_patches = [
        p.patch for p in await repo.get_patches("run_evidence_patch")
        if p.namespace == "evidence"
    ]

    assert evidence_patches
    assert set(evidence_patches[0]) == {
        "evidence_id",
        "type",
        "store_ref",
        "summary",
        "redaction_status",
    }
    forbidden = ("raw", "secret", "password", "jdbc:", "postgres://", "connection string")
    assert not any(token in str(evidence_patches).lower() for token in forbidden)


@pytest.mark.asyncio
async def test_state_repo_failure_does_not_block_stage() -> None:
    class FailingStateRepository:
        async def append(self, *args, **kwargs):
            raise RuntimeError("state repo unavailable")

    await _run_with_state_repo(
        run_id="run_state_failure",
        state_repo=FailingStateRepository(),
    )


@pytest.mark.asyncio
async def test_event_persist_failure_publishes_sanitized_final_error() -> None:
    class FailingEventRepository:
        async def append(self, *args, **kwargs):
            raise RuntimeError('duplicate key value violates unique constraint "run_event_run_id_seq_key"')

    published = []
    bus = EventBus()
    bus.publish = AsyncMock(side_effect=lambda run_id, event: published.append(event))  # type: ignore[method-assign]
    run_repo = AsyncMock()

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_event_repo,
        patch("app.workflow.runner.get_state_repo") as mock_get_state_repo,
    ):
        mock_router.return_value = _router_out()
        mock_get_sup.return_value = _FixedSupervisor([])
        mock_get_event_repo.return_value = FailingEventRepository()
        mock_get_state_repo.return_value = InMemoryStateRepository()

        await run_workflow(
            run_id="run_event_persist_failure",
            user_message="현재 상태 요약",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
        )

    status_updates = [call.args for call in run_repo.update_status.await_args_list]
    assert ("run_event_persist_failure", "failed", None) in status_updates
    assert published
    assert all("duplicate key" not in event.message.lower() for event in published)
    assert all("duplicate key" not in str(event.payload).lower() for event in published)
    assert published[-1].payload == {"error": "workflow_failed"}


@pytest.mark.asyncio
async def test_classifier_patch_namespace_authoring() -> None:
    repo = await _run_with_state_repo(
        run_id="run_classifier_patch",
        state_repo=InMemoryStateRepository(),
        mode=AgentMode.INCIDENT_ANALYSIS,
    )

    classifier_patches = [
        p for p in await repo.get_patches("run_classifier_patch")
        if p.author == "Classifier" and p.path == "/analysis/incident_types"
    ]

    assert len(classifier_patches) == 1
    assert classifier_patches[0].namespace == "analysis"
    assert classifier_patches[0].patch["incident_types"][0]["type"] == "latency_spike"


@pytest.mark.asyncio
async def test_run_budget_exceeded_emits_run_guards_patch() -> None:
    repo = await _run_with_state_repo(
        run_id="run_budget_patch",
        state_repo=InMemoryStateRepository(),
        supervisor=Supervisor(store=InMemoryStateStore(), policy=RetryPolicy(max_steps=1)),
    )

    guard_patches = [
        p for p in await repo.get_patches("run_budget_patch")
        if p.namespace == "run" and p.path == "/run/guards"
    ]

    assert len(guard_patches) == 1
    assert guard_patches[0].author == "Supervisor"
    assert guard_patches[0].op == "version"
    assert guard_patches[0].patch == {"reason": "step_budget"}


@pytest.mark.asyncio
async def test_requested_incident_context_forces_incident_mode_and_report_link() -> None:
    state_repo = InMemoryStateRepository()
    report_repo = _CapturingReportRepository()
    supervisor = _RecordingSupervisor(["report"])
    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    run_repo = AsyncMock()
    run_repo.get.return_value = SimpleNamespace(incident_id=None, remediation_requested=False)

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_event_repo,
        patch("app.workflow.runner.get_state_repo") as mock_get_state_repo,
        patch("app.workflow.runner.get_llm_provider"),
        patch("app.workflow.runner.get_report_repo") as mock_get_report_repo,
    ):
        mock_router.return_value = _router_out(AgentMode.SIMPLE_QUERY, remediation_requested=False)
        mock_report.return_value = "incident report"
        mock_get_sup.return_value = supervisor
        mock_get_event_repo.return_value = InMemoryEventRepository()
        mock_get_state_repo.return_value = state_repo
        mock_get_report_repo.return_value = report_repo

        await run_workflow(
            run_id="run_requested_incident",
            user_message="상세 분석",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=AsyncMock(),
            requested_incident_id="inc_001",
            requested_remediation_requested=True,
        )

    assert supervisor.start_args == ("run_requested_incident", AgentMode.INCIDENT_ANALYSIS)
    # #882 remediation 요청이 켜진 incident 컨텍스트는 remediation_planning depth 로 보정된다.
    assert supervisor.start_kwargs == {
        "incident_id": "inc_001",
        "remediation_requested": True,
        "execution_depth": ExecutionDepth.REMEDIATION_PLANNING,
    }
    assert report_repo.created[0][1]["incident_id"] == "inc_001"
    incident_patches = [
        p for p in await state_repo.get_patches("run_requested_incident")
        if p.namespace == "incident" and p.path == "/incident/incident_id"
    ]
    assert incident_patches[0].patch == {"incident_id": "inc_001", "mode": "incident_analysis"}
