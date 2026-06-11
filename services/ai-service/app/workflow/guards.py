"""Supervisor budget guards (§15.5.1 루프 방지와 종료 보장).

Every stage entry point calls check_all_global first. Any guard violation raises
RunBudgetExceeded, which the Supervisor catches and converts to a forced Report.
"""
from __future__ import annotations

from datetime import datetime, timezone

from app.schemas.state import AgentState
from app.supervisor.retry_policy import RetryPolicy


class RunBudgetExceeded(Exception):
    """Raised when any run-level guard limit is reached."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _now(now: datetime | None) -> datetime:
    return now or datetime.now(timezone.utc)


def check_step_budget(state: AgentState, max_steps: int) -> None:
    if state.run.step_count >= max_steps:
        raise RunBudgetExceeded("step_budget")


def check_wall_clock_budget(
    state: AgentState, max_seconds: float, *, now: datetime | None = None
) -> None:
    """누적 실행 시간이 wall-clock 예산을 넘었으면 종료한다(#481).

    ``started_at``이 없으면(아직 run이 시작 시각을 기록하지 않은 상태) 검사하지
    않는다. ``max_seconds`` <= 0이면 guard 비활성.
    """
    started = state.run.started_at
    if started is None or max_seconds <= 0:
        return
    if (_now(now) - started).total_seconds() >= max_seconds:
        raise RunBudgetExceeded("wall_clock_timeout")


def check_stage_timeout(
    state: AgentState, max_seconds: float, *, now: datetime | None = None
) -> None:
    """단일 stage가 stage 예산을 넘겨 머물렀으면 종료한다(#481).

    ``stage_started_at``은 advance가 새 stage로 전이할 때 갱신된다. 다음 advance
    진입 시점에 직전 stage의 체류 시간을 검사하므로, 멈춘(혹은 지나치게 느린)
    stage가 run을 무한히 붙잡지 못한다.
    """
    started = state.run.stage_started_at
    if started is None or max_seconds <= 0:
        return
    if (_now(now) - started).total_seconds() >= max_seconds:
        raise RunBudgetExceeded("stage_timeout")


def check_llm_call_budget(state: AgentState, max_calls: int) -> None:
    if max_calls > 0 and state.run.llm_call_count >= max_calls:
        raise RunBudgetExceeded("llm_call_budget")


def check_token_budget(state: AgentState, max_tokens: int) -> None:
    if max_tokens > 0 and state.run.token_count >= max_tokens:
        raise RunBudgetExceeded("token_budget")


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
    # #481: step/loop 카운터에 더해 시간·자원 예산도 매 stage 진입마다 집행한다.
    # 어느 하나라도 초과하면 RunBudgetExceeded → Supervisor가 run을 안전 종료한다.
    check_wall_clock_budget(state, policy.wall_clock_timeout_seconds)
    check_stage_timeout(state, policy.stage_timeout_seconds)
    check_llm_call_budget(state, policy.max_llm_calls)
    check_token_budget(state, policy.max_tokens)
    # NOTE(#453, #476): fail_loops/gap_loops/scope_loops/revise_action_loops는 매
    # stage 진입마다 검사하지 않는다. loopback(§9 Verifier, §3 Classifier
    # scope_unclear·Policy Guard revise_action)은 책임 Agent로 되돌아간 뒤 다시
    # 같은 분기점에 도달하는 정상 경로이므로, 카운터가 상한에 닿은 채로도 그 한 번의
    # loopback은 끝까지 실행돼야 한다. 진입 시점에 검사하면 마지막 loopback이 실행
    # 직전에 차단된다. 따라서 loopback 예산 집행은 진입 시점이 아니라 Supervisor의
    # 전이 결정 지점(record_verifier_result / record_classifier_result /
    # record_policy_guard_result)에서 _force_fail로 수행한다. 개별 check_*_loops
    # 함수는 명시적 검사를 위해 그대로 보존한다.
