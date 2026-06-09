"""RCA agent skeleton — Root Cause Catalog 기준 후보 검증."""
from __future__ import annotations

from app.schemas.outputs import ClassifierOutput, RcaOutput, RetrievalOutput
from app.schemas.state import RootCauseCandidate


async def run_rca(classifier_out: ClassifierOutput | None, retrieval_out: RetrievalOutput | None) -> RcaOutput:
    return RcaOutput(
        root_cause_candidates=[
            RootCauseCandidate(
                root_cause_id="UNKNOWN",
                confidence=0.0,
                required_evidence_satisfied=False,
                evidence_gap=["additional_evidence_required"],
                explanation="skeleton: evidence_gap — LLM 기반 RCA 미구현",
            )
        ]
    )
