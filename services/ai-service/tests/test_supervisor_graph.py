from __future__ import annotations

import pytest

from app.schemas.state import AgentMode, RunStatus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.guards import RunBudgetExceeded, check_fail_loops, check_gap_loops, check_revision_budget


def _supervisor(max_steps: int = 24) -> tuple[Supervisor, InMemoryStateStore]:
    store = InMemoryStateStore()
    policy = RetryPolicy(max_steps=max_steps)
    return Supervisor(store=store, policy=policy), store


def test_simple_query_advances_through_stages():
    sup, store = _supervisor()
    sup.start_run("run_001", AgentMode.SIMPLE_QUERY)

    stages = []
    nxt = sup.advance("run_001")
    while nxt is not None:
        stages.append(nxt)
        nxt = sup.advance("run_001")

    assert stages == ["planner", "retrieval", "verifier", "report"]
    state = store.get("run_001")
    assert state is not None
    assert state.run.status == RunStatus.COMPLETED
    assert state.run.step_count == 5  # 4 stages + 1 final None advance


def test_step_budget_raises_at_limit():
    sup, store = _supervisor(max_steps=2)
    sup.start_run("run_001", AgentMode.SIMPLE_QUERY)

    sup.advance("run_001")  # step_count → 1
    sup.advance("run_001")  # step_count → 2

    with pytest.raises(RunBudgetExceeded) as exc_info:
        sup.advance("run_001")

    assert exc_info.value.reason == "step_budget"
    state = store.get("run_001")
    assert state is not None
    assert state.run.status == RunStatus.FAILED


def test_revision_guard_raises_at_limit():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    policy = RetryPolicy(max_revisions=2)

    state.run.guards.revision_counts["rca"] = 2

    with pytest.raises(RunBudgetExceeded) as exc_info:
        check_revision_budget(state, "rca", policy.max_revisions)

    assert exc_info.value.reason == "revision_limit"


def test_gap_loops_guard_raises():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    policy = RetryPolicy(max_gap_loops=2)

    state.run.guards.gap_loops = 2

    with pytest.raises(RunBudgetExceeded) as exc_info:
        check_gap_loops(state, policy.max_gap_loops)

    assert exc_info.value.reason == "gap_loops"


def test_fail_loops_guard_raises():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    policy = RetryPolicy(max_fail_loops=1)

    state.run.guards.fail_loops = 1

    with pytest.raises(RunBudgetExceeded) as exc_info:
        check_fail_loops(state, policy.max_fail_loops)

    assert exc_info.value.reason == "fail_loops"
