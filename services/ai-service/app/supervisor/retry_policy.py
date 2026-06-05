"""Guard budget constants for the Supervisor (§15.5.1 루프 방지와 종료 보장)."""
from __future__ import annotations

from dataclasses import dataclass

from app.core.config import settings


@dataclass(frozen=True)
class RetryPolicy:
    max_steps: int = 24
    max_revisions: int = 2
    max_fail_loops: int = 1
    max_gap_loops: int = 2
    max_scope_loops: int = 2
    max_revise_action_loops: int = 2
    wall_clock_timeout_seconds: float = 300.0


def default_retry_policy() -> RetryPolicy:
    return RetryPolicy(
        max_steps=settings.max_steps_per_run,
        max_revisions=settings.max_revisions,
    )
