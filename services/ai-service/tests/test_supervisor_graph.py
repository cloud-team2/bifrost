from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.schemas.state import AgentMode, RunStatus
from app.supervisor.graph import Supervisor
from app.supervisor.retry_policy import RetryPolicy
from app.supervisor.state_store import InMemoryStateStore
from app.workflow.guards import (
    RunBudgetExceeded,
    check_fail_loops,
    check_gap_loops,
    check_llm_call_budget,
    check_revision_budget,
    check_stage_timeout,
    check_token_budget,
    check_wall_clock_budget,
)


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


def test_wall_clock_budget_raises_when_elapsed():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    state.run.started_at = datetime.now(timezone.utc) - timedelta(seconds=10)

    with pytest.raises(RunBudgetExceeded) as exc_info:
        check_wall_clock_budget(state, 5.0)

    assert exc_info.value.reason == "wall_clock_timeout"


def test_wall_clock_budget_noop_without_started_at():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    # started_at None → 검사하지 않는다.
    check_wall_clock_budget(state, 0.001)


def test_stage_timeout_raises_when_stage_stalls():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    state.run.stage_started_at = datetime.now(timezone.utc) - timedelta(seconds=30)

    with pytest.raises(RunBudgetExceeded) as exc_info:
        check_stage_timeout(state, 10.0)

    assert exc_info.value.reason == "stage_timeout"


def test_llm_call_budget_raises_at_limit():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    state.run.llm_call_count = 16

    with pytest.raises(RunBudgetExceeded) as exc_info:
        check_llm_call_budget(state, 16)

    assert exc_info.value.reason == "llm_call_budget"


def test_token_budget_raises_at_limit():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    state.run.token_count = 200_000

    with pytest.raises(RunBudgetExceeded) as exc_info:
        check_token_budget(state, 200_000)

    assert exc_info.value.reason == "token_budget"


def test_disabled_budgets_are_noop():
    store = InMemoryStateStore()
    state = store.create("run_001", AgentMode.SIMPLE_QUERY)
    state.run.token_count = 10_000
    state.run.llm_call_count = 10
    state.run.stage_started_at = datetime.now(timezone.utc) - timedelta(seconds=999)
    # 0 이하이면 비활성 — 예외 없이 통과.
    check_token_budget(state, 0)
    check_llm_call_budget(state, 0)
    check_stage_timeout(state, 0)


def test_advance_terminates_run_on_wall_clock_timeout():
    store = InMemoryStateStore()
    policy = RetryPolicy(wall_clock_timeout_seconds=5.0)
    sup = Supervisor(store=store, policy=policy)
    sup.start_run("run_001", AgentMode.SIMPLE_QUERY)
    # start_run이 기록한 started_at을 과거로 밀어 wall-clock 초과를 만든다.
    store.get("run_001").run.started_at = datetime.now(timezone.utc) - timedelta(seconds=10)

    with pytest.raises(RunBudgetExceeded) as exc_info:
        sup.advance("run_001")

    assert exc_info.value.reason == "wall_clock_timeout"
    assert store.get("run_001").run.status == RunStatus.FAILED


def test_start_run_records_started_at():
    store = InMemoryStateStore()
    sup = Supervisor(store=store, policy=RetryPolicy())
    sup.start_run("run_001", AgentMode.SIMPLE_QUERY)
    assert store.get("run_001").run.started_at is not None


def test_record_llm_usage_accumulates_and_enforces_budget():
    store = InMemoryStateStore()
    policy = RetryPolicy(max_llm_calls=2)
    sup = Supervisor(store=store, policy=policy)
    sup.start_run("run_001", AgentMode.SIMPLE_QUERY)

    sup.record_llm_usage("run_001", calls=1, tokens=100)
    sup.record_llm_usage("run_001", calls=1, tokens=50)
    state = store.get("run_001")
    assert state.run.llm_call_count == 2
    assert state.run.token_count == 150

    with pytest.raises(RunBudgetExceeded) as exc_info:
        sup.advance("run_001")
    assert exc_info.value.reason == "llm_call_budget"


def test_record_llm_usage_ignores_unknown_run():
    store = InMemoryStateStore()
    sup = Supervisor(store=store, policy=RetryPolicy())
    # 존재하지 않는 run — 예외 없이 무시.
    sup.record_llm_usage("missing", calls=1, tokens=10)


def test_incident_analysis_advances_through_stages():
    sup, store = _supervisor()
    sup.start_run("run_001", AgentMode.INCIDENT_ANALYSIS)

    stages = []
    nxt = sup.advance("run_001")
    while nxt is not None:
        stages.append(nxt)
        nxt = sup.advance("run_001")

    assert stages == ["correlation", "planner", "retrieval", "classifier", "rca", "verifier", "report"]
    state = store.get("run_001")
    assert state is not None
    assert state.run.status == RunStatus.COMPLETED
    assert state.run.step_count == 8  # 7 stages + 1 final None advance


def test_incident_analysis_remediation_advances_through_stages():
    sup, store = _supervisor()
    sup.start_run("run_001", AgentMode.INCIDENT_ANALYSIS, remediation_requested=True)

    stages = []
    nxt = sup.advance("run_001")
    while nxt is not None:
        stages.append(nxt)
        nxt = sup.advance("run_001")

    assert stages == [
        "correlation", "planner", "retrieval", "classifier", "rca",
        "remediation", "policy_guard", "verifier", "report",
    ]
    state = store.get("run_001")
    assert state is not None
    assert state.run.status == RunStatus.COMPLETED
