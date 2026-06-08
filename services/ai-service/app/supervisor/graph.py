"""Supervisor — workflow controller (§15 Workflow Control).

Orchestrates stage sequencing, enforces loop guards, and manages AgentState.
Does NOT contain LLM logic; actual agent execution is delegated in later issues.
"""
from __future__ import annotations

from app.schemas.state import AgentMode, AgentState, RunStatus
from app.supervisor.retry_policy import RetryPolicy, default_retry_policy
from app.supervisor.state_store import InMemoryStateStore, get_state_store
from app.supervisor.transitions import next_stage
from app.workflow.guards import RunBudgetExceeded, check_all_global


class Supervisor:
    def __init__(
        self,
        store: InMemoryStateStore | None = None,
        policy: RetryPolicy | None = None,
    ) -> None:
        self._store = store or get_state_store()
        self._policy = policy or default_retry_policy()

    def start_run(
        self,
        run_id: str,
        mode: AgentMode,
        incident_id: str | None = None,
    ) -> AgentState:
        return self._store.create(run_id, mode, incident_id)

    def get_state(self, run_id: str) -> AgentState | None:
        return self._store.get(run_id)

    def advance(self, run_id: str) -> str | None:
        """Advance the run by one stage and return the next stage name.

        Returns None when the run is complete (no more stages).
        Raises RunBudgetExceeded if any guard limit is hit.
        """
        state = self._store.get(run_id)
        if state is None:
            raise KeyError(f"Run not found: {run_id}")

        try:
            check_all_global(state, self._policy)
        except RunBudgetExceeded:
            self._store.patch(run_id, lambda s: _mark_failed(s))
            raise

        mode = AgentMode(state.run.status) if False else _infer_mode(state)
        current = state.run.current_agent
        nxt = next_stage(mode, current)

        def _apply(s: AgentState) -> AgentState:
            s.run.step_count += 1
            s.run.current_agent = nxt
            if nxt is None:
                s.run.status = RunStatus.COMPLETED
            return s

        self._store.patch(run_id, _apply)
        return nxt


def _infer_mode(state: AgentState) -> AgentMode:
    return state.run.mode


def _mark_failed(state: AgentState) -> AgentState:
    state.run.status = RunStatus.FAILED
    return state


_supervisor = Supervisor()


def get_supervisor() -> Supervisor:
    return _supervisor
