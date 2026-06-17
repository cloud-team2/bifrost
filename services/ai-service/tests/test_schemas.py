"""Schema contract smoke tests for issue #111."""
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from app.schemas.events import StreamingEvent, StreamingEventType
from app.schemas.outputs import (
    ActionCandidateOutput,
    ExecutionResultOutput,
    PlannerOutput,
    PolicyDecisionOutput,
    ReportOutput,
)
from app.schemas.state import (
    ActionStatus,
    ActionType,
    AgentState,
    EvidenceItem,
    EvidenceType,
    PatchOperation,
    PolicyDecisionType,
    RiskLevel,
    RunState,
    StateNamespace,
    StatePatch,
)
from app.schemas.tools import (
    ConsumerLagData,
    ConsumerLagPartition,
    ListProjectPipelinesData,
    LogSearchRequest,
    MutationToolRequest,
    PipelineConnectorRef,
    PipelineDependencyRef,
    PipelineTopologyData,
    ProjectPipelineSummary,
    ReadToolRequest,
    SpringOpsResponse,
    ToolContext,
)


def test_agent_state_skeleton_defaults():
    state = AgentState(run=RunState(run_id="run_001"))

    assert state.run.status == "running"
    assert state.run.guards.revision_counts == {}
    assert state.run.plan.executed_plan_hashes == []
    assert state.incident.incident_id is None
    assert state.correlation.related_alert_ids == []
    assert state.evidence.items == []
    assert state.actions.candidates == []
    assert state.actions.approval_requests == []
    assert state.verification.verification_results == []
    assert state.report.final is None


def test_state_patch_contract_for_namespace_append():
    patch = StatePatch(
        patch_id="patch_001",
        run_id="run_001",
        agent="Retrieval",
        namespace=StateNamespace.EVIDENCE,
        operation=PatchOperation.APPEND,
        path="/evidence/items",
        value_ref="ev_log_001",
        created_at=datetime.now(UTC),
    )

    assert patch.namespace == StateNamespace.EVIDENCE
    assert patch.operation == PatchOperation.APPEND
    assert patch.model_dump()["value_ref"] == "ev_log_001"


def test_state_patch_rejects_inline_value():
    with pytest.raises(ValidationError):
        StatePatch(
            run_id="run_001",
            agent="Retrieval",
            namespace=StateNamespace.EVIDENCE,
            operation=PatchOperation.APPEND,
            path="/evidence/items",
            value={"raw": "inline"},
            created_at=datetime.now(UTC),
        )


def test_evidence_item_has_reference_without_raw_content():
    evidence = EvidenceItem(
        evidence_id="ev_log_001",
        type=EvidenceType.PIPELINE_LOG,
        store_ref="evidence://run_001/ev_log_001",
        summary="connector task timeout summary",
        collected_by="Retrieval",
        collected_at=datetime.now(UTC),
    )

    assert evidence.redaction_status == "redacted"
    assert "content" not in evidence.model_dump()

    with pytest.raises(ValidationError):
        EvidenceItem(
            evidence_id="ev_log_002",
            type=EvidenceType.PIPELINE_LOG,
            store_ref="evidence://run_001/ev_log_002",
            summary="raw log omitted",
            content="raw log line",
        )


def test_streaming_event_envelope_and_enum_serialization():
    event = StreamingEvent(
        event_id="evt_001",
        run_id="run_001",
        timestamp=datetime.now(UTC),
        type=StreamingEventType.TOOL_CALL_COMPLETED,
        agent="Retrieval",
        message="connector status evidence collected",
        payload={"tool": "get_connector_status", "evidence_id": "ev_001"},
    )

    dumped = event.model_dump(mode="json")

    assert dumped["type"] == "tool_call_completed"
    assert dumped["payload"]["tool"] == "get_connector_status"


def test_planner_step_requires_params_depends_on_and_plan_hash():
    valid = PlannerOutput(
        retrieval_plan=[
            {
                "step_id": "plan_001",
                "tool_name": "get_consumer_lag",
                "params": {"consumer_group": "orders-consumer"},
                "purpose": "consumer lag evidence",
                "required": True,
                "depends_on": [],
                "plan_hash": "sha256:1b7c",
            }
        ]
    )

    assert valid.retrieval_plan[0].depends_on == []

    for missing_field in ("params", "depends_on", "plan_hash"):
        step = {
            "step_id": "plan_001",
            "tool_name": "get_consumer_lag",
            "params": {"consumer_group": "orders-consumer"},
            "purpose": "consumer lag evidence",
            "required": True,
            "depends_on": [],
            "plan_hash": "sha256:1b7c",
        }
        step.pop(missing_field)
        with pytest.raises(ValidationError):
            PlannerOutput(retrieval_plan=[step])


def test_runtime_action_requires_tool_name():
    with pytest.raises(ValidationError):
        ActionCandidateOutput(
            action_id="act_001",
            action_type=ActionType.RUNTIME_TOOL,
            action_name="restart_connector_task",
            risk=RiskLevel.MEDIUM,
            reason="connector task failed",
        )


def test_policy_and_executor_status_contracts():
    decision = PolicyDecisionOutput(
        action_id="act_001",
        action_type=ActionType.RUNTIME_TOOL,
        tool_name="restart_connector_task",
        risk=RiskLevel.MEDIUM,
        decision=PolicyDecisionType.REQUIRE_APPROVAL,
        status=ActionStatus.PENDING_APPROVAL,
        reason="runtime state change requires approval",
    )
    result = ExecutionResultOutput(
        action_id="act_001",
        tool_name="restart_connector_task",
        status=ActionStatus.COMPLETED,
        summary="connector task restart requested",
    )

    assert decision.status == ActionStatus.PENDING_APPROVAL
    assert result.status == ActionStatus.COMPLETED

    with pytest.raises(ValidationError):
        PolicyDecisionOutput(
            action_id="act_002",
            action_type=ActionType.RUNTIME_TOOL,
            risk=RiskLevel.LOW,
            decision=PolicyDecisionType.DENY,
            status=ActionStatus.READY,
            reason="deny must be blocked",
        )

    with pytest.raises(ValidationError):
        ExecutionResultOutput(
            action_id="act_003",
            tool_name="restart_connector_task",
            status=ActionStatus.RUNNING,
            summary="running is only an intermediate state",
        )


def test_tool_context_headers_and_read_request_contract():
    context = ToolContext(
        run_id="run_001",
        step_id="step_001",
        agent_name="Retrieval",
        project_id="9efc8e1e-3df5-4b25-a7d2-6df29ef9508d",
        request_id="req_001",
    )
    request = ReadToolRequest(
        tool_name="get_consumer_lag",
        params={"consumer_group": "orders-consumer"},
        context=context,
    )

    headers = context.spring_headers()

    assert headers["X-Agent-Run-Id"] == "run_001"
    assert headers["X-Agent-Step-Id"] == "step_001"
    assert headers["X-Agent-Name"] == "Retrieval"
    assert headers["X-Request-Id"] == "req_001"
    assert headers["X-Actor-Type"] == "agent"
    assert headers["X-Actor-Id"] == "bifrost-agent"
    assert request.idempotency_key is None
    assert request.spring_headers()["X-Agent-Run-Id"] == "run_001"


def test_mutation_request_requires_idempotency_header():
    context = ToolContext(
        run_id="run_001",
        step_id="step_009",
        agent_name="Executor",
        project_id="9efc8e1e-3df5-4b25-a7d2-6df29ef9508d",
        request_id="req_009",
    )
    request = MutationToolRequest(
        tool_name="restart_connector_task",
        params={"connector_name": "extract-users", "task_id": 0},
        context=context,
        idempotency_key="idem_001",
    )

    assert request.spring_headers()["X-Idempotency-Key"] == "idem_001"

    with pytest.raises(ValidationError):
        MutationToolRequest(
            tool_name="restart_connector_task",
            params={"connector_name": "extract-users", "task_id": 0},
            context=context,
        )


def test_spring_ops_envelope_and_read_tool_payloads():
    pipelines = ListProjectPipelinesData(
        pipelines=[
            ProjectPipelineSummary(
                pipeline_id="pipe_001",
                name="daily_user_sync",
                pattern="direct",
                source_db_id="db_src",
                status="active",
            )
        ]
    )
    topology = PipelineTopologyData(
        pipeline_id="pipe_001",
        pattern="direct",
        source=PipelineDependencyRef(db_id="db_src", alias="source"),
        connectors=[PipelineConnectorRef(cr_name="extract-users", kind="source", state="RUNNING")],
        topics=["dbserver1.users"],
        status="active",
    )
    lag = ConsumerLagData(
        consumer_group="orders-consumer",
        total_lag=42,
        p95_lag=42.0,
        partitions=[
            ConsumerLagPartition(
                topic="orders",
                partition=0,
                current_offset=100,
                log_end_offset=142,
                lag=42,
            )
        ],
        top_lag_partitions=[
            ConsumerLagPartition(
                topic="orders",
                partition=0,
                current_offset=100,
                log_end_offset=142,
                lag=42,
            )
        ],
        summary="consumer lag snapshot: lag p95=42",
    )
    logs_request = LogSearchRequest(
        query="timeout",
        time_range={"from": "2026-06-01T00:00:00Z", "to": "2026-06-01T00:30:00Z"},
        pipeline_id="pipe_001",
        limit=20,
    )
    response = SpringOpsResponse(
        ok=True,
        request_id="req_001",
        operation="get_consumer_lag",
        result=lag.model_dump(mode="json"),
        evidence=[
            {
                "evidence_id": "ev_metric_001",
                "store_ref": "evidence://run_001/ev_metric_001",
                "summary": "consumer lag snapshot",
                "redaction_status": "redacted",
                "type": "metric",
            }
        ],
        audit_event_id="audit_001",
    )

    assert pipelines.pipelines[0].pattern == "direct"
    assert topology.connectors[0].state == "RUNNING"
    assert logs_request.time_range.from_ == "2026-06-01T00:00:00Z"
    assert response.result["total_lag"] == 42
    assert response.result["p95_lag"] == 42.0
    assert response.evidence[0].store_ref == "evidence://run_001/ev_metric_001"


def test_report_output_requires_incident_final_response_fields():
    report = ReportOutput(
        final_response={
            "incident_id": "inc_001",
            "summary": "source DB connection timeout is most likely",
            "root_cause_id": "SOURCE_DB_CONNECTION_TIMEOUT",
            "confidence": 0.82,
            "evidence": [{"evidence_id": "ev_log_001", "summary": "extract task timeout log"}],
            "actions": [
                {
                    "action_id": "act_001",
                    "risk": "medium",
                    "estimated_duration": "about 30 seconds",
                    "status": "pending_approval",
                }
            ],
            "limitations": ["customer DB internals were not inspected"],
        }
    )

    assert report.final_response.incident_id == "inc_001"

    with pytest.raises(ValidationError):
        ReportOutput(
            final_response={
                "summary": "missing required incident fields",
                "evidence": [],
                "actions": [],
                "limitations": [],
            }
        )
