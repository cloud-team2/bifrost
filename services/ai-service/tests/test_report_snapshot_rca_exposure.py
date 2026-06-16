"""#767 — the RCA root_cause_id + confidence must be exposed on the public report
snapshot (top-level columns AND inside the report body), not only in internal
state. The report API returns ``snapshot.model_dump(mode="json")`` so the body
is the public surface consumed by callers/evals.
"""
from __future__ import annotations

import uuid

import pytest

from app.persistence.report_repository import get_report_repo
from app.schemas.outputs import (
    RcaOutput,
    RetrievalOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.schemas.state import (
    AgentMode,
    RootCauseCandidate,
    VerificationStatus,
)
from app.workflow.runner import _persist_report_snapshot


def _rca() -> RcaOutput:
    return RcaOutput(
        root_cause_candidates=[
            RootCauseCandidate(
                root_cause_id="SOURCE_AUTH_EXPIRED",
                confidence=0.84,
                required_evidence_satisfied=True,
                supporting_evidence_ids=["ev-1"],
                evidence_gap=[],
                explanation="source 인증 만료로 추정",
            )
        ]
    )


def _verifier_pass() -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id="v-1",
                target="report",
                status=VerificationStatus.PASS,
                approved_for_final_response=True,
                reason="ok",
            )
        ]
    )


@pytest.mark.asyncio
async def test_report_snapshot_exposes_rca_on_public_surface() -> None:
    run_id = f"run-{uuid.uuid4().hex[:8]}"

    await _persist_report_snapshot(
        run_id=run_id,
        answer="원인 후보: SOURCE_AUTH_EXPIRED",
        mode=AgentMode.INCIDENT_ANALYSIS,
        retrieval_out=RetrievalOutput(evidence_items=[]),
        rca_out=_rca(),
        verifier_out=_verifier_pass(),
        incident_id="inc-1",
    )

    snapshot = await get_report_repo().get_latest(run_id)
    assert snapshot is not None

    # Top-level columns
    assert snapshot.root_cause_id == "SOURCE_AUTH_EXPIRED"
    assert snapshot.confidence == pytest.approx(0.84)

    # Public report body (the surface returned by /agent/runs/{run_id}/report)
    payload = snapshot.model_dump(mode="json")
    body = payload["body"]
    assert body["root_cause"]["root_cause_id"] == "SOURCE_AUTH_EXPIRED"
    assert body["root_cause"]["confidence"] == pytest.approx(0.84)
    assert body["root_cause_candidates"]
    assert body["root_cause_candidates"][0]["root_cause_id"] == "SOURCE_AUTH_EXPIRED"
    assert body["root_cause_candidates"][0]["confidence"] == pytest.approx(0.84)
