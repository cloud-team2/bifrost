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
    # NOTE(#453, #476): fail_loops/gap_loops/scope_loops/revise_action_loops는 매
    # stage 진입마다 검사하지 않는다. loopback(§9 Verifier, §3 Classifier
    # scope_unclear·Policy Guard revise_action)은 책임 Agent로 되돌아간 뒤 다시
    # 같은 분기점에 도달하는 정상 경로이므로, 카운터가 상한에 닿은 채로도 그 한 번의
    # loopback은 끝까지 실행돼야 한다. 진입 시점에 검사하면 마지막 loopback이 실행
    # 직전에 차단된다. 따라서 loopback 예산 집행은 진입 시점이 아니라 Supervisor의
    # 전이 결정 지점(record_verifier_result / record_classifier_result /
    # record_policy_guard_result)에서 _force_fail로 수행한다. 개별 check_*_loops
    # 함수는 명시적 검사를 위해 그대로 보존한다.
