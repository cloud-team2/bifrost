"""Verifier agent tests."""
from __future__ import annotations

from datetime import datetime, timezone

import pytest

from app.agents.verifier import run_verifier
from app.schemas.outputs import RcaOutput, RetrievalOutput
from app.schemas.state import (
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
