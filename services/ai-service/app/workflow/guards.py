"""Supervisor budget guards (§15.5.1 루프 방지와 종료 보장).

Every stage entry point calls check_all_global first. Any guard violation raises
RunBudgetExceeded, which the Supervisor catches and converts to a forced Report.
"""
from __future__ import annotations

from app.schemas.state import AgentState
from app.supervisor.retry_policy import RetryPolicy


class RunBudgetExceeded(Exception):
    """Raised when any run-level guard limit is reached."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def check_step_budget(state: AgentState, max_steps: int) -> None:
    if state.run.step_count >= max_steps:
        raise RunBudgetExceeded("step_budget")


def check_revision_budget(state: AgentState, target: str, max_revisions: int) -> None:
    if state.run.guards.revision_counts.get(target, 0) >= max_revisions:
        raise RunBudgetExceeded("revision_limit")


def check_gap_loops(state: AgentState, max_gap_loops: int) -> None:
    if state.run.guards.gap_loops >= max_gap_loops:
        raise RunBudgetExceeded("gap_loops")


def check_fail_loops(state: AgentState, max_fail_loops: int) -> None:
    if state.run.guards.fail_loops >= max_fail_loops:
        raise RunBudgetExceeded("fail_loops")


def check_scope_loops(state: AgentState, max_scope_loops: int) -> None:
    if state.run.guards.scope_loops >= max_scope_loops:
        raise RunBudgetExceeded("scope_loops")


def check_revise_action_loops(state: AgentState, max_revise_action_loops: int) -> None:
    if state.run.guards.revise_action_loops >= max_revise_action_loops:
        raise RunBudgetExceeded("revise_action_loops")


def check_all_global(state: AgentState, policy: RetryPolicy) -> None:
    check_step_budget(state, policy.max_steps)
