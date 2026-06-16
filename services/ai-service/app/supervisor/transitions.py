"""Stage transition tables per AgentMode (§15 §4 Branch 규칙)."""
from __future__ import annotations

from app.schemas.state import AgentMode

SIMPLE_QUERY_STAGES: tuple[str, ...] = ("planner", "retrieval", "verifier", "report")

INCIDENT_ANALYSIS_STAGES: tuple[str, ...] = (
    "correlation", "planner", "retrieval",
    "classifier", "rca", "verifier", "report",
)

INCIDENT_ANALYSIS_REMEDIATION_SUFFIX: tuple[str, ...] = (
    "remediation",
    "policy_guard",
    "approval_gate",
)

ACTION_EXECUTION_STAGES: tuple[str, ...] = (
    "policy_guard", "approval_gate", "change_gate",
    "executor", "verifier", "report",
)

APPROVAL_DECISION_STAGES: tuple[str, ...] = (
    "approval_gate", "executor", "verifier", "report",
)


def stages_for_mode(mode: AgentMode, remediation_requested: bool = False) -> tuple[str, ...]:
    if mode == AgentMode.SIMPLE_QUERY:
        return SIMPLE_QUERY_STAGES
    if mode == AgentMode.INCIDENT_ANALYSIS:
        base = INCIDENT_ANALYSIS_STAGES
        if remediation_requested:
            idx = base.index("rca") + 1
            return base[:idx] + INCIDENT_ANALYSIS_REMEDIATION_SUFFIX + base[idx:]
        return base
    if mode == AgentMode.ACTION_EXECUTION:
        return ACTION_EXECUTION_STAGES
    if mode == AgentMode.APPROVAL_DECISION:
        return APPROVAL_DECISION_STAGES
    return ()


def next_stage(mode: AgentMode, current: str | None, remediation_requested: bool = False) -> str | None:
    stages = stages_for_mode(mode, remediation_requested)
    if not stages:
        return None
    if current is None:
        return stages[0]
    try:
        idx = stages.index(current)
    except ValueError:
        return None
    next_idx = idx + 1
    return stages[next_idx] if next_idx < len(stages) else None
