"""Verifier agent — simple_query always passes."""
from __future__ import annotations

from uuid import uuid4

from app.schemas.outputs import VerificationResultOutput, VerifierOutput
from app.schemas.state import AgentMode, VerificationStatus


async def run_verifier(mode: AgentMode) -> VerifierOutput:
    if mode == AgentMode.SIMPLE_QUERY:
        status = VerificationStatus.PASS
        reason = "simple_query 경로 — 조회 결과를 그대로 통과"
        approved = True
    else:
        status = VerificationStatus.PASS
        reason = "골격 단계 — 항상 통과 (full verifier는 추후 구현)"
        approved = True

    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id=str(uuid4()),
                target="retrieval_result",
                status=status,
                approved_for_final_response=approved,
                reason=reason,
            )
        ]
    )
