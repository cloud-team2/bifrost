"""Supervisor — workflow controller (§15 Workflow Control).

Orchestrates stage sequencing, enforces loop guards, and manages AgentState.
Does NOT contain LLM logic; actual agent execution is delegated in later issues.
"""
from __future__ import annotations

from app.schemas.state import AgentMode, AgentState, RunStatus, VerificationStatus
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
        self._remediation_flags: dict[str, bool] = {}
        # #453 Verifier loopback: 다음 advance에서 정적 테이블 대신 되돌릴 stage.
        self._pending_loopback: dict[str, str] = {}
        # #453 loopback 예산 소진 시 다음 advance에서 올릴 RunBudgetExceeded reason.
        self._force_fail: dict[str, str] = {}

    def start_run(
        self,
        run_id: str,
        mode: AgentMode,
        incident_id: str | None = None,
        remediation_requested: bool = False,
    ) -> AgentState:
        self._remediation_flags[run_id] = remediation_requested
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

        # #453: Verifier loopback 예산이 소진됐으면 검증 안 된 결과를 Report로 보내지
        # 않고 run을 failed로 종료한다(§10 Stop 조건).
        forced = self._force_fail.pop(run_id, None)
        if forced is not None:
            self._store.patch(run_id, lambda s: _mark_failed(s))
            raise RunBudgetExceeded(forced)

        try:
            check_all_global(state, self._policy)
        except RunBudgetExceeded:
            self._store.patch(run_id, lambda s: _mark_failed(s))
            raise

        # #453: Verifier fail/needs_revision으로 등록된 loopback이 있으면 정적
        # 테이블 대신 책임 Agent로 되돌린다.
        nxt = self._pending_loopback.pop(run_id, None)
        if nxt is None:
            mode = _infer_mode(state)
            current = state.run.current_agent
            remediation_requested = self._remediation_flags.get(run_id, False)
            nxt = next_stage(mode, current, remediation_requested)

        def _apply(s: AgentState) -> AgentState:
            s.run.step_count += 1
            s.run.current_agent = nxt
            if nxt is None:
                s.run.status = RunStatus.COMPLETED
            return s

        self._store.patch(run_id, _apply)
        return nxt

    def record_verifier_result(
        self,
        run_id: str,
        status: VerificationStatus,
        next_agent: str | None,
    ) -> None:
        """Verifier 결과를 다음 전이에 반영한다(§9 Verifier Loop).

        - ``pass``: 그대로 Report로 진행(loopback 없음).
        - ``fail``: ``fail_loops`` 예산 내에서 ``next_agent``로 loopback. 초과 시
          다음 advance에서 ``RunBudgetExceeded("fail_loops")``로 run failed.
        - ``needs_revision``: ``gap_loops`` 예산 내에서 ``next_agent``로 loopback.
          초과 시 ``RunBudgetExceeded("gap_loops")``로 run failed.

        종료 보장(§5.1): loopback은 fail_loops/gap_loops 상한으로, 그 외 모든
        경로는 step 예산으로 유한 종료된다.
        """
        state = self._store.get(run_id)
        if state is None:
            raise KeyError(f"Run not found: {run_id}")

        # 직전 stage에서 남았을 수 있는 loopback 등록을 초기화.
        self._pending_loopback.pop(run_id, None)

        if status == VerificationStatus.PASS or next_agent is None:
            return

        if status == VerificationStatus.FAIL:
            counter, limit = "fail_loops", self._policy.max_fail_loops
        else:  # NEEDS_REVISION
            counter, limit = "gap_loops", self._policy.max_gap_loops

        if getattr(state.run.guards, counter) >= limit:
            # 예산 소진 → 다음 advance에서 failed 종료(검증 안 된 Report 차단).
            self._force_fail[run_id] = counter
            return

        def _bump(s: AgentState) -> AgentState:
            setattr(s.run.guards, counter, getattr(s.run.guards, counter) + 1)
            return s

        self._store.patch(run_id, _bump)
        self._pending_loopback[run_id] = next_agent


def _infer_mode(state: AgentState) -> AgentMode:
    return state.run.mode


def _mark_failed(state: AgentState) -> AgentState:
    state.run.status = RunStatus.FAILED
    return state


_supervisor = Supervisor()


def get_supervisor() -> Supervisor:
    return _supervisor
