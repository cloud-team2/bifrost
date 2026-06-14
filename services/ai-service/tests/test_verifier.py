"""Verifier agent tests."""
from __future__ import annotations

from datetime import datetime, timezone

import pytest

from app.agents.verifier import run_verifier
from app.persistence.evidence_repository import InMemoryEvidenceRepository
from app.schemas.outputs import ExecutionResultOutput, ExecutorOutput, RcaOutput, RetrievalOutput
from app.schemas.state import (
    ActionStatus,
    AgentMode,
    EvidenceItem,
    EvidenceType,
    RedactionStatus,
    RootCauseCandidate,
    VerificationStatus,
)


def _evidence(evidence_id: str, summary: str) -> EvidenceItem:
    return EvidenceItem(
        evidence_id=evidence_id,
        type=EvidenceType.TOOL_RESULT,
        store_ref=f"evidence://test/{evidence_id}",
        summary=summary,
        redaction_status=RedactionStatus.REDACTED,
        collected_by="test",
        collected_at=datetime.now(timezone.utc),
    )


def _candidate(
    root_cause_id: str,
    *,
    confidence: float = 0.8,
    required_evidence_satisfied: bool = True,
) -> RootCauseCandidate:
    return RootCauseCandidate(
        root_cause_id=root_cause_id,
        confidence=confidence,
        required_evidence_satisfied=required_evidence_satisfied,
        supporting_evidence_ids=[],
        negative_evidence_ids=[],
        evidence_gap=[],
        explanation="test candidate",
    )


def _status(output):
    return output.verification_results[0].status


def _execution_result(
    *,
    action_id: str = "act_001",
    status: ActionStatus = ActionStatus.COMPLETED,
    after_evidence_id: str | None = "evidence://after/act_001/ok",
    reason_code: str | None = None,
    summary: str = "completed",
) -> ExecutionResultOutput:
    return ExecutionResultOutput(
        action_id=action_id,
        tool_name="restart_connector",
        status=status,
        audit_event_id="audit_001",
        before_evidence_id="evidence://before/act_001/ok",
        after_evidence_id=after_evidence_id,
        reason_code=reason_code,
        summary=summary,
    )


@pytest.mark.asyncio
async def test_simple_query_still_pass() -> None:
    output = await run_verifier(AgentMode.SIMPLE_QUERY)

    result = output.verification_results[0]
    assert result.status == VerificationStatus.PASS
    assert result.approved_for_final_response is True


@pytest.mark.asyncio
async def test_incident_full_evidence_pass() -> None:
    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(root_cause_candidates=[_candidate("SOURCE_AUTH_EXPIRED")]),
        retrieval_out=RetrievalOutput(
            evidence_items=[
                _evidence("ev-auth", "auth/permission error log AccessDenied token expired")
            ]
        ),
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.PASS
    assert result.approved_for_final_response is True


@pytest.mark.asyncio
async def test_incident_evidence_matrix_uses_hydrated_raw_payload() -> None:
    evidence_repo = InMemoryEvidenceRepository()
    await evidence_repo.put(
        run_id="test",
        evidence_id="ev-auth",
        tool_name="search_logs",
        step_id="s1",
        status="success",
        payload={"logs": ["auth/permission error log AccessDenied token expired"]},
    )

    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(root_cause_candidates=[_candidate("SOURCE_AUTH_EXPIRED")]),
        retrieval_out=RetrievalOutput(
            evidence_items=[
                _evidence("ev-auth", "redacted log evidence available")
            ]
        ),
        evidence_repo=evidence_repo,
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.PASS
    assert result.approved_for_final_response is True


@pytest.mark.asyncio
async def test_incident_connector_task_failed_accepts_structured_status_payload() -> None:
    evidence_repo = InMemoryEvidenceRepository()
    store_ref = await evidence_repo.put(
        run_id="test",
        evidence_id="ev-connector-status",
        tool_name="get_connector_status",
        step_id="s1",
        status="success",
        payload={
            "connectorName": "e2e-hitl-test-source",
            "connectorState": "RUNNING",
            "tasks": [
                {
                    "id": 0,
                    "state": "FAILED",
                    "trace": (
                        "io.debezium.DebeziumException: Creation of replication slot failed. "
                        "org.postgresql.util.PSQLException: ERROR: all replication slots are in use"
                    ),
                }
            ],
        },
    )

    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(root_cause_candidates=[_candidate("CONNECTOR_TASK_FAILED")]),
        retrieval_out=RetrievalOutput(
            evidence_items=[
                EvidenceItem(
                    evidence_id="ev-connector-status",
                    type=EvidenceType.TOOL_RESULT,
                    store_ref=store_ref,
                    summary="get_connector_status completed (tasks: 1)",
                    redaction_status=RedactionStatus.REDACTED,
                    collected_by="retrieval",
                    collected_at=datetime.now(timezone.utc),
                )
            ]
        ),
        evidence_repo=evidence_repo,
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.PASS
    assert result.approved_for_final_response is True


@pytest.mark.asyncio
async def test_incident_passes_when_one_actionable_candidate_is_evidence_backed() -> None:
    evidence_repo = InMemoryEvidenceRepository()
    store_ref = await evidence_repo.put(
        run_id="test",
        evidence_id="ev-connector-status",
        tool_name="get_connector_status",
        step_id="s1",
        status="success",
        payload={
            "connectorName": "orders-source",
            "connectorState": "RUNNING",
            "tasks": [
                {
                    "id": 0,
                    "state": "FAILED",
                    "trace": "org.postgresql.util.PSQLException: ERROR: all replication slots are in use",
                }
            ],
        },
    )

    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(
            root_cause_candidates=[
                _candidate("CONNECTOR_TASK_FAILED"),
                _candidate(
                    "SOURCE_AUTH_EXPIRED",
                    confidence=0.61,
                    required_evidence_satisfied=False,
                ),
            ]
        ),
        retrieval_out=RetrievalOutput(
            evidence_items=[
                EvidenceItem(
                    evidence_id="ev-connector-status",
                    type=EvidenceType.TOOL_RESULT,
                    store_ref=store_ref,
                    summary="get_connector_status completed (tasks: 1)",
                    redaction_status=RedactionStatus.REDACTED,
                    collected_by="retrieval",
                    collected_at=datetime.now(timezone.utc),
                )
            ]
        ),
        evidence_repo=evidence_repo,
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.PASS
    assert result.approved_for_final_response is True


@pytest.mark.asyncio
async def test_incident_required_missing_needs_revision() -> None:
    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(root_cause_candidates=[_candidate("SOURCE_AUTH_EXPIRED")]),
        retrieval_out=RetrievalOutput(evidence_items=[]),
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.NEEDS_REVISION
    assert result.next_agent == "planner"
    assert result.approved_for_final_response is False
    assert "auth/permission error log" in result.reason


@pytest.mark.asyncio
async def test_incident_low_confidence_fail() -> None:
    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(
            root_cause_candidates=[
                _candidate("SOURCE_AUTH_EXPIRED", confidence=0.59),
                _candidate("SCHEMA_MISMATCH", confidence=0.3),
            ]
        ),
        retrieval_out=RetrievalOutput(
            evidence_items=[
                _evidence("ev-auth", "auth/permission error log"),
                _evidence("ev-schema", "serialization/deserialization/schema error"),
            ]
        ),
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.FAIL
    assert result.next_agent == "planner"
    assert result.approved_for_final_response is False


@pytest.mark.asyncio
async def test_unknown_with_gap_needs_revision() -> None:
    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(
            root_cause_candidates=[
                _candidate("UNKNOWN_WITH_EVIDENCE_GAP", confidence=0.0)
            ]
        ),
        retrieval_out=RetrievalOutput(evidence_items=[]),
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.NEEDS_REVISION
    assert result.next_agent == "planner"
    assert result.approved_for_final_response is False


@pytest.mark.asyncio
async def test_llm_unavailable_rule_only() -> None:
    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(root_cause_candidates=[_candidate("SCHEMA_MISMATCH")]),
        retrieval_out=RetrievalOutput(
            evidence_items=[
                _evidence("ev-schema", "serialization/deserialization/schema error")
            ]
        ),
    )

    assert _status(output) == VerificationStatus.PASS


@pytest.mark.asyncio
async def test_approved_for_final_response_only_when_pass() -> None:
    for output in [
        await run_verifier(
            AgentMode.INCIDENT_ANALYSIS,
            rca_out=RcaOutput(root_cause_candidates=[]),
            retrieval_out=RetrievalOutput(evidence_items=[]),
        ),
        await run_verifier(
            AgentMode.INCIDENT_ANALYSIS,
            rca_out=RcaOutput(root_cause_candidates=[_candidate("NOT_IN_CATALOG")]),
            retrieval_out=RetrievalOutput(evidence_items=[]),
        ),
    ]:
        result = output.verification_results[0]
        assert result.status != VerificationStatus.PASS
        assert result.approved_for_final_response is False


@pytest.mark.asyncio
async def test_action_execution_missing_after_evidence_needs_revision() -> None:
    output = await run_verifier(
        AgentMode.ACTION_EXECUTION,
        executor_out=ExecutorOutput(
            execution_results=[
                _execution_result(after_evidence_id=None),
            ]
        ),
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.NEEDS_REVISION
    assert result.next_agent == "executor"
    assert "after evidence" in result.reason


@pytest.mark.asyncio
async def test_action_execution_validates_failed_result_reason_consistency() -> None:
    output = await run_verifier(
        AgentMode.ACTION_EXECUTION,
        executor_out=ExecutorOutput(
            execution_results=[
                _execution_result(
                    status=ActionStatus.FAILED,
                    reason_code=None,
                    summary="completed successfully",
                ),
            ]
        ),
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.NEEDS_REVISION
    assert result.next_agent == "executor"
    assert "불일치" in result.reason


@pytest.mark.asyncio
async def test_action_execution_with_after_evidence_passes() -> None:
    output = await run_verifier(
        AgentMode.ACTION_EXECUTION,
        executor_out=ExecutorOutput(
            execution_results=[
                _execution_result(summary="connector restarted"),
            ]
        ),
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.PASS
    assert result.approved_for_final_response is True


@pytest.mark.asyncio
async def test_report_unverified_root_cause_needs_revision() -> None:
    output = await run_verifier(
        AgentMode.INCIDENT_ANALYSIS,
        rca_out=RcaOutput(root_cause_candidates=[_candidate("SOURCE_AUTH_EXPIRED")]),
        retrieval_out=RetrievalOutput(
            evidence_items=[
                _evidence("ev-auth", "auth/permission error log AccessDenied token expired")
            ]
        ),
        report_body="진단 결론: root_cause_id=SCHEMA_MISMATCH 입니다.",
    )

    result = output.verification_results[0]
    assert result.status == VerificationStatus.NEEDS_REVISION
    assert result.next_agent == "report"
    assert "검증되지 않은" in result.reason
