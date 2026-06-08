"""Remediation agent skeleton — Remediation Runbooks 기준 action 후보 작성."""
from __future__ import annotations

from app.schemas.outputs import ActionCandidateOutput, RcaOutput, RemediationOutput
from app.schemas.state import ActionType, RiskLevel


async def run_remediation(rca_out: RcaOutput | None) -> RemediationOutput:
    return RemediationOutput(
        action_candidates=[
            ActionCandidateOutput(
                action_id="act_escalation",
                action_type=ActionType.ESCALATION,
                action_name="escalate_to_operator",
                risk=RiskLevel.LOW,
                reason="skeleton: Remediation Runbooks 미구현 — escalation",
                expected_effect="manual operator review",
            )
        ]
    )
