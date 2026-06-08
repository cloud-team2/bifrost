"""Stage transition tables per AgentMode (§15 §4 Branch 규칙).

Only simple_query is implemented in this skeleton. Other modes are filled in
during the Supervisor full implementation issue.
"""
from __future__ import annotations

from app.schemas.state import AgentMode

SIMPLE_QUERY_STAGES: tuple[str, ...] = ("planner", "retrieval", "verifier", "report")

_STAGE_SEQUENCES: dict[AgentMode, tuple[str, ...]] = {
    AgentMode.SIMPLE_QUERY: SIMPLE_QUERY_STAGES,
}


def stages_for_mode(mode: AgentMode) -> tuple[str, ...]:
    return _STAGE_SEQUENCES.get(mode, ())


def next_stage(mode: AgentMode, current: str | None) -> str | None:
    stages = stages_for_mode(mode)
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
