"""run_workflow() e2e integration tests for incident_analysis flow."""
from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

import pytest

from app.persistence.event_repository import InMemoryEventRepository
from app.persistence.approval_link_repository import get_approval_repo
from app.persistence.run_repository import InMemoryRunRepository
from app.persistence.state_repository import InMemoryStateRepository
from app.schemas.events import StreamingEventType
from app.schemas.outputs import (
    Classification,
    ClassifierOutput,
    ExecutionResultOutput,
    ExecutorOutput,
    IncidentTypeOutput,
    PlannerOutput,
    PolicyGuardOutput,
    RcaOutput,
    RemediationOutput,
    ActionCandidateOutput,
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
    VerificationStatus,
)
from app.streaming.event_bus import EventBus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.runner import run_workflow


def _router_out(
    mode: AgentMode = AgentMode.INCIDENT_ANALYSIS,
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
                plan_hash="abc",
            )
        ]
    )


@pytest.mark.asyncio
async def test_unresolved_connector_identifier_completes_without_gap_loop() -> None:
    bus = EventBus()
    published = []

    async def _capture(run_id: str, evt):
        published.append(evt)

    bus.publish = _capture  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()
    event_repo = InMemoryEventRepository()
    supervisor = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
    message = "커넥터 이름을 알려주세요."

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
    ):
        mock_get_sup.return_value = supervisor
        mock_get_repo.return_value = event_repo
        mock_router.return_value = _router_out(mode=AgentMode.SIMPLE_QUERY)
        mock_planner.return_value = PlannerOutput(retrieval_plan=[], clarification_message=message)

        await run_workflow(
            run_id="run_489",
            user_message="consumer lag 확인해줘",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    mock_retrieval.assert_not_awaited()
    mock_verifier.assert_not_awaited()
    mock_report.assert_not_awaited()
    assert supervisor.get_state("run_489").run.guards.gap_loops == 0
    assert ("run_489", "completed", None) in [call.args for call in run_repo.update_status.call_args_list]
    completed = [event for event in published if event.type == StreamingEventType.RUN_COMPLETED]
    assert completed[-1].payload["answer"] == message


def _retrieval_out() -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev1",
                type=EvidenceType.TOOL_RESULT,
                store_ref="evidence://run/s1",
                summary="test metric",
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
            incident_types=[IncidentTypeOutput(type="unknown", confidence=0.0)],
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
                evidence_gap=[],
                explanation="test root cause",
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


def _remediation_out() -> RemediationOutput:
    return RemediationOutput(
        action_candidates=[
            ActionCandidateOutput(
                action_id="act_001",
                action_type=ActionType.ESCALATION,
                action_name="escalate_to_operator",
                risk=RiskLevel.LOW,
                reason="test",
                expected_effect="manual review",
            )
        ]
    )


@pytest.mark.asyncio
async def test_incident_analysis_run_emits_expected_event_sequence() -> None:
    bus = EventBus()
    published = []

    async def _capture(run_id: str, evt):
        published.append(evt)

    bus.publish = _capture  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()
    event_repo = InMemoryEventRepository()

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.classifier_agent.run_classifier", new_callable=AsyncMock) as mock_classifier,
        patch("app.workflow.runner.rca_agent.run_rca", new_callable=AsyncMock) as mock_rca,
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = event_repo
        mock_router.return_value = _router_out()
        mock_planner.return_value = _planner_out()
        mock_retrieval.return_value = _retrieval_out()
        mock_classifier.return_value = _classifier_out()
        mock_rca.return_value = _rca_out()
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "분석 완료"

        await run_workflow(
            run_id="run_001",
            user_message="서버 장애가 발생했습니다",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    types = [e.type for e in published]

    # 기본 이벤트 존재
    assert StreamingEventType.RUN_STARTED in types
    assert StreamingEventType.RUN_COMPLETED in types

    # 7단계 AGENT_STARTED 순서
    started_agents = [e.agent for e in published if e.type == StreamingEventType.AGENT_STARTED]
    for stage in ["correlation", "planner", "retrieval", "classifier", "rca", "verifier", "report"]:
        assert stage in started_agents
    assert started_agents.index("rca") < started_agents.index("verifier")

    # PARTIAL_RESULT: planner·classifier·rca 각 발행
    pr_stages = [e.payload.get("stage") for e in published if e.type == StreamingEventType.PARTIAL_RESULT]
    assert "planner" in pr_stages
    assert "classifier" in pr_stages
    assert "rca" in pr_stages

    # #604: router 완료 payload에 전체 stage 흐름이 실린다(FE 진행 분모 고정용).
    router_completed = next(
        e for e in published
        if e.type == StreamingEventType.AGENT_COMPLETED and e.agent == "router"
    )
    assert router_completed.payload["required_flow"] == [
        "correlation", "planner", "retrieval", "classifier", "rca", "verifier", "report",
    ]
    assert router_completed.payload["total_stages"] == 7

    # #670: REPORT_PREVIEW_AVAILABLE은 verifier PASS 이후 검증된 preview만 발행
    rpa_events = [e for e in published if e.type == StreamingEventType.REPORT_PREVIEW_AVAILABLE]
    assert len(rpa_events) == 1
    assert rpa_events[0].payload["verified"] is True
    assert rpa_events[0].payload["root_cause_id"] == "rc_001"

    rpa_idx = types.index(StreamingEventType.REPORT_PREVIEW_AVAILABLE)
    vc_idx = types.index(StreamingEventType.VERIFICATION_COMPLETED)
    assert vc_idx < rpa_idx


@pytest.mark.asyncio
async def test_incident_analysis_remediation_emits_approval_required() -> None:
    bus = EventBus()
    published = []

    async def _capture(run_id: str, evt):
        published.append(evt)

    bus.publish = _capture  # type: ignore[method-assign]
    run_repo = AsyncMock()
    registry = AsyncMock()
    event_repo = InMemoryEventRepository()

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
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = event_repo
        mock_router.return_value = _router_out(remediation_requested=True)
        mock_planner.return_value = _planner_out()
        mock_retrieval.return_value = _retrieval_out()
        mock_classifier.return_value = _classifier_out()
        mock_rca.return_value = _rca_out()
        mock_remediation.return_value = _remediation_out()
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "분석 완료"

        await run_workflow(
            run_id="run_002",
            user_message="서버 장애 — 조치 요청",
            project_id="proj_001",
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

    types = [e.type for e in published]

    # remediation·policy_guard·approval_gate 단계 포함 10단계
    started_agents = [e.agent for e in published if e.type == StreamingEventType.AGENT_STARTED]
    assert "remediation" in started_agents
    assert "policy_guard" in started_agents
    assert "approval_gate" in started_agents

    # #604: remediation 요청 run은 required_flow에 remediation/policy_guard 포함.
    router_completed = next(
        e for e in published
        if e.type == StreamingEventType.AGENT_COMPLETED and e.agent == "router"
    )
    assert "remediation" in router_completed.payload["required_flow"]
    assert "policy_guard" in router_completed.payload["required_flow"]
    assert "approval_gate" in router_completed.payload["required_flow"]
    assert router_completed.payload["total_stages"] == 10

    # APPROVAL_REQUIRED 발행
    approval_events = [e for e in published if e.type == StreamingEventType.APPROVAL_REQUIRED]
    assert len(approval_events) >= 1
    assert "action_id" in approval_events[0].payload
    assert approval_events[0].payload["action_id"] == "act_001"


@pytest.mark.asyncio
async def test_incident_analysis_full_loop_persists_approval_bridge_and_executes() -> None:
    run_id = "run_766_incident_bridge"
    incident_id = "inc_766"
    project_id = "proj_001"
    connector_name = "orders-source-connector"

    run_repo = InMemoryRunRepository()
    await run_repo.create(
        run_id,
        "incident_analysis",
        project_id=project_id,
        incident_id=incident_id,
        remediation_requested=True,
    )
    state_repo = InMemoryStateRepository()
    event_repo = InMemoryEventRepository()
    bus = EventBus()
    bus.publish = AsyncMock()  # type: ignore[method-assign]
    registry = AsyncMock()
    executed_candidates: list = []

    retrieval_out = RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev_connector_status",
                type=EvidenceType.TOOL_RESULT,
                store_ref=f"tool://get_connector_status?connector_name={connector_name}",
                summary=(
                    "get_connector_status completed "
                    f"(tasks: 1, connector_name={connector_name}, state=FAILED)"
                ),
                redaction_status=RedactionStatus.REDACTED,
                collected_by="retrieval",
                collected_at=datetime.now(timezone.utc),
            )
        ]
    )
    rca_out = RcaOutput(
        root_cause_candidates=[
            RootCauseCandidate(
                root_cause_id="CONNECTOR_TASK_FAILED",
                confidence=0.91,
                required_evidence_satisfied=True,
                evidence_gap=[],
                explanation="connector task failed",
            )
        ]
    )

    async def _capture_executor(candidates, **_kwargs):
        executed_candidates.extend(candidates)
        return ExecutorOutput(
            execution_results=[
                ExecutionResultOutput(
                    action_id=candidates[0].action_id,
                    tool_name=candidates[0].tool_name,
                    status=ActionStatus.COMPLETED,
                    summary="restart completed",
                )
            ]
        )

    with (
        patch("app.workflow.runner.router_agent.run_router", new_callable=AsyncMock) as mock_router,
        patch("app.workflow.runner.planner_agent.run_planner", new_callable=AsyncMock) as mock_planner,
        patch("app.workflow.runner.retrieval_agent.run_retrieval", new_callable=AsyncMock) as mock_retrieval,
        patch("app.workflow.runner.classifier_agent.run_classifier", new_callable=AsyncMock) as mock_classifier,
        patch("app.workflow.runner.rca_agent.run_rca", new_callable=AsyncMock) as mock_rca,
        patch("app.workflow.runner.run_executor", new=AsyncMock(side_effect=_capture_executor)),
        patch("app.workflow.runner.verifier_agent.run_verifier", new_callable=AsyncMock) as mock_verifier,
        patch("app.workflow.runner.report_agent.run_report", new_callable=AsyncMock) as mock_report,
        patch("app.workflow.runner.get_supervisor") as mock_get_sup,
        patch("app.workflow.runner.get_event_repo") as mock_get_repo,
        patch("app.workflow.runner.get_state_repo", return_value=state_repo),
        patch("app.workflow.runner.get_llm_provider"),
    ):
        mock_get_sup.return_value = Supervisor(store=InMemoryStateStore(), policy=RetryPolicy())
        mock_get_repo.return_value = event_repo
        mock_router.side_effect = [
            _router_out(mode=AgentMode.INCIDENT_ANALYSIS, remediation_requested=True),
            _router_out(mode=AgentMode.APPROVAL_DECISION),
        ]
        mock_planner.return_value = _planner_out()
        mock_retrieval.return_value = retrieval_out
        mock_classifier.return_value = _classifier_out()
        mock_rca.return_value = rca_out
        mock_verifier.return_value = _verifier_out()
        mock_report.return_value = "완료"

        await run_workflow(
            run_id=run_id,
            user_message=f"{connector_name} connector task failed. 조치 후보 보여줘",
            project_id=project_id,
            bus=bus,
            run_repo=run_repo,
            registry=registry,
        )

        patches = await state_repo.get_patches(run_id)
        candidates_patch = next(
            patch.patch for patch in patches
            if patch.path == "/actions/candidates"
        )
        restart_candidate = next(
            candidate for candidate in candidates_patch["candidates"]
            if candidate["action_name"] == "restart_connector"
        )
        assert restart_candidate["tool_params"] == {"connector_name": connector_name}

        approval_repo = get_approval_repo()
        pending = approval_repo.list_pending(run_id)
        restart_link = next(
            link for link in pending
            if link.action_id == restart_candidate["action_id"]
        )
        approval_patch = [
            patch.patch for patch in await state_repo.get_patches(run_id)
            if patch.path == "/actions/approval_requests"
        ][-1]
        assert {
            "approval_id": restart_link.approval_id,
            "action_id": restart_candidate["action_id"],
            "params_hash": restart_link.params_hash,
            "approval_status": "pending",
        } in approval_patch["approval_requests"]
        assert (await run_repo.get(run_id)).status == "waiting_for_approval"

        approval_repo.approve(restart_link.approval_id)
        await run_workflow(
            run_id=run_id,
            user_message="승인할게",
            project_id=project_id,
            bus=bus,
            run_repo=run_repo,
            registry=registry,
            requested_mode="approval_decision",
            requested_incident_id=incident_id,
        )

    assert len(executed_candidates) == 1
    executed = executed_candidates[0]
    assert executed.action_id == restart_candidate["action_id"]
    assert executed.tool_name == "restart_connector"
    assert executed.tool_params == {"connector_name": connector_name}
    assert executed.status == ActionStatus.READY
