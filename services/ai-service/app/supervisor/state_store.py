"""In-memory AgentState store. Replaced by PostgreSQL in the persistence issue (⑤)."""
from __future__ import annotations

from typing import Callable

from app.schemas.state import AgentMode, AgentState, IncidentState, RunState


class RunNotFound(KeyError):
    pass


class InMemoryStateStore:
    def __init__(self) -> None:
        self._store: dict[str, AgentState] = {}

    def create(
        self,
        run_id: str,
        mode: AgentMode,
        incident_id: str | None = None,
    ) -> AgentState:
        state = AgentState(
            run=RunState(run_id=run_id),
            incident=IncidentState(incident_id=incident_id),
        )
        state.run.current_agent = None
        self._store[run_id] = state
        return state

    def get(self, run_id: str) -> AgentState | None:
        return self._store.get(run_id)

    def patch(self, run_id: str, fn: Callable[[AgentState], AgentState]) -> AgentState:
        state = self._store.get(run_id)
        if state is None:
            raise RunNotFound(run_id)
        updated = fn(state)
        self._store[run_id] = updated
        return updated


_store = InMemoryStateStore()


def get_state_store() -> InMemoryStateStore:
    return _store
