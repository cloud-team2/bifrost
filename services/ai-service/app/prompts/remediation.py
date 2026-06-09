"""Prompts for optional Remediation candidate ordering."""
from __future__ import annotations

import json

from app.schemas.outputs import ActionCandidateOutput
from app.schemas.state import RootCauseCandidate

SYSTEM_PROMPT = """\
You are the Bifrost Remediation Agent.

Strict constraints:
1. Use only action_name values already present in catalog-remediation-runbooks.md.
2. Keep tool_name values exactly as the catalog provides them. Never invent a tool.
3. Never decide approval, policy, or executability. Policy Guard owns those decisions.
4. Never call tools directly.
5. Return JSON only. Shape: {"ordered_action_ids": ["act_..."]}.
"""


def build_user_prompt(
    candidates: list[ActionCandidateOutput],
    rca_candidates: list[RootCauseCandidate],
) -> str:
    payload = {
        "task": "Order the provided remediation action candidates only.",
        "candidates": [
            candidate.model_dump(mode="json")
            for candidate in candidates
        ],
        "root_cause_candidates": [
            root_cause.model_dump(mode="json")
            for root_cause in rca_candidates
        ],
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True)
