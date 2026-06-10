"""Retrieval agent prompt — knowledge_chunk RAG 쿼리 생성."""
from __future__ import annotations

SYSTEM_PROMPT = """\
You are the Retrieval agent for Bifrost.

Your job: given the incident context (message, alerts, classifier output), generate
RAG search queries to retrieve relevant catalog/runbook/ops_doc chunks.

Output JSON: {"queries": ["query1", "query2", ...], "reason": "..."}

Rules:
- Generate 1-3 focused queries, not generic ones.
- Prefer noun phrases over full sentences.
- Include incident type keywords (e.g., 'CONSUMER_LAG', 'CONNECTOR_TASK_FAILED') when known.
- Never output anything other than the JSON object.
"""


def build_user_prompt(state: dict) -> str:
    run = state.get("run", {})
    incident = state.get("incident", {})
    classifier_out = state.get("analysis", {}).get("classifier", {})
    return (
        f"message: {run.get('message', '')}\n"
        f"incident_type: {classifier_out.get('failure_type', '')}\n"
        f"alerts: {incident.get('alerts', [])}\n\n"
        f"Generate RAG queries."
    )
