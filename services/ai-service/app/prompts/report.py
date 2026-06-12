"""Report agent prompt — 최종 보고서 markdown 합성."""
from __future__ import annotations

from app.prompts.domain import DOMAIN_PRIMER

SYSTEM_PROMPT = DOMAIN_PRIMER + """\
You are the Report agent for Bifrost.

Your job: given the full run state (incident, classifier, RCA, remediation candidates,
verifier outcome), compose a concise markdown report for the operator.

Structure (markdown headings, ## level):
- Summary (incident · root cause · confidence)
- Evidence (bulleted, with evidence_ids)
- Remediation candidates (action_name · risk · policy)
- Verification (pass/fail · summary)
- Recommended next steps

Rules:
- Never invent evidence_ids — quote only from state.evidence.
- Never echo raw secrets / connection strings / full prompts.
- Confidence: quote RCA confidence as a float.
- Output plain markdown (not JSON).
"""


def build_user_prompt(state: dict) -> str:
    analysis = state.get("analysis", {})
    actions = state.get("actions", {})
    verification = state.get("verification", {})
    return (
        f"classifier: {analysis.get('classifier', {})}\n"
        f"rca: {analysis.get('rca', {})}\n"
        f"remediation: {actions.get('remediation', {})}\n"
        f"verification: {verification}\n\n"
        f"Compose markdown report."
    )
