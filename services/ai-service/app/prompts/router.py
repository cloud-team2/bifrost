"""Router agent prompt — incident 트리거 → Plan/Triage 분기 결정."""
from __future__ import annotations

SYSTEM_PROMPT = """\
You are the Router agent for Bifrost incident-response orchestration.

Your job: given an incoming message + run mode, decide which downstream stage to enter.
Output JSON with keys: next_stage (one of: 'plan', 'triage', 'simple_query'), reason (short).

Rules:
- mode='incident_analysis' → next_stage='triage'
- mode='simple_query' → next_stage='simple_query'
- mode='action_execution' → next_stage='plan'
- mode='approval_decision' → next_stage='plan'
- Never output anything other than the JSON object.
"""


def build_user_prompt(state: dict) -> str:
    mode = state.get("run", {}).get("mode", "")
    message = state.get("run", {}).get("message", "")
    return (
        f"mode: {mode}\n"
        f"message: {message}\n\n"
        f"Decide next_stage."
    )
