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


@pytest.mark.asyncio
async def test_incident_reports_fall_back_to_unverified_pause_report() -> None:
    """#932 — 승인 대기로 verified 리포트가 없을 때, incident reports 는 unverified
    pause 리포트(RCA+권장조치)로 폴백해 노출한다(권장조치가 비어 보이지 않게)."""
    from app.api.routes_reports import list_incident_reports

    incident_id = f"inc-{uuid.uuid4().hex[:8]}"
    run_id = f"run-{uuid.uuid4().hex[:8]}"

    await _persist_report_snapshot(
        run_id=run_id,
        answer="원인 후보 + 권장조치",
        mode=AgentMode.INCIDENT_ANALYSIS,
        retrieval_out=RetrievalOutput(evidence_items=[]),
        rca_out=_rca(),
        verifier_out=None,  # 승인 전 — verifier 미실행 → verified=False
        incident_id=incident_id,
    )

    # 기본(verified_only=True)으로는 제외된다.
    assert await get_report_repo().list_by_incident(incident_id) == []

    # 라우트는 verified 가 없으면 unverified 로 폴백해 노출한다.
    resp = await list_incident_reports(incident_id)
    reports = resp.model_dump(mode="json")["data"]["reports"]
    assert len(reports) == 1
    assert reports[0]["root_cause_id"] == "SOURCE_AUTH_EXPIRED"
