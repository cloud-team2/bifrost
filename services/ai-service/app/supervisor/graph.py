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

    def _register_loopback(
        self,
        run_id: str,
        *,
        counter: str,
        limit: int,
        next_agent: str | None,
    ) -> None:
        """공통 loopback 등록기(§5.1 루프 가드).

        ``next_agent``가 있으면 ``counter`` 예산 내에서 그 stage로 되돌릴 loopback을
        등록하고 카운터를 증가시킨다. 예산이 이미 소진됐으면 다음 advance에서
        ``RunBudgetExceeded(counter)``를 올리도록 ``_force_fail``에 표시한다.
        ``next_agent``가 ``None``이면(정상 진행) 직전 등록만 초기화하고 끝낸다.

        진입 시점이 아니라 이 전이 결정 지점에서 예산을 집행하므로, 카운터가 상한에
        닿은 채로도 마지막 loopback 한 번은 끝까지 실행된다(§3 flowchart 순환).
        """
        state = self._store.get(run_id)
        if state is None:
            raise KeyError(f"Run not found: {run_id}")

        # 직전 stage에서 남았을 수 있는 loopback 등록을 초기화.
        self._pending_loopback.pop(run_id, None)

        if next_agent is None:
            return

        if getattr(state.run.guards, counter) >= limit:
            # 예산 소진 → 다음 advance에서 failed 종료(유한 종료 보장).
            self._force_fail[run_id] = counter
            return

        self._store.patch(run_id, lambda s: _bump_guard(s, counter))
        self._pending_loopback[run_id] = next_agent

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
        if status == VerificationStatus.PASS:
            next_agent = None  # pass는 절대 loopback하지 않는다.
            counter, limit = "gap_loops", self._policy.max_gap_loops
        elif status == VerificationStatus.FAIL:
            counter, limit = "fail_loops", self._policy.max_fail_loops
        else:  # NEEDS_REVISION
            counter, limit = "gap_loops", self._policy.max_gap_loops

        self._register_loopback(run_id, counter=counter, limit=limit, next_agent=next_agent)

    def record_classifier_result(self, run_id: str, *, scope_unclear: bool) -> None:
        """Classifier scope_unclear → Planner 재수집 loopback(§3 'scope 불명확').

        scope_unclear이면 ``scope_loops`` 예산 내에서 Planner로 되돌려 evidence를
        더 수집한다. 초과 시 다음 advance에서 ``RunBudgetExceeded("scope_loops")``로
        run failed(유한 종료). scope가 명확하면 정적 테이블대로 RCA로 진행한다.
        """
        next_agent = "planner" if scope_unclear else None
        self._register_loopback(
            run_id,
            counter="scope_loops",
            limit=self._policy.max_scope_loops,
            next_agent=next_agent,
        )

    def record_policy_guard_result(self, run_id: str, *, revise_action: bool) -> None:
        """Policy Guard revise_action → Remediation 재생성 loopback(§3 revise_action).

        revise_action이면 ``revise_action_loops`` 예산 내에서 Remediation으로 되돌려
        더 안전한 조치 후보를 재생성한다. 초과 시 다음 advance에서
        ``RunBudgetExceeded("revise_action_loops")``로 run failed(유한 종료).
        정상 결정이면 정적 테이블대로 Verifier로 진행한다.
        """
        next_agent = "remediation" if revise_action else None
        self._register_loopback(
            run_id,
            counter="revise_action_loops",
            limit=self._policy.max_revise_action_loops,
            next_agent=next_agent,
        )


def _bump_guard(state: AgentState, counter: str) -> AgentState:
    setattr(state.run.guards, counter, getattr(state.run.guards, counter) + 1)
    return state


def _infer_mode(state: AgentState) -> AgentMode:
    return state.run.mode


def _mark_failed(state: AgentState) -> AgentState:
    state.run.status = RunStatus.FAILED
    return state


_supervisor = Supervisor()


def get_supervisor() -> Supervisor:
    return _supervisor
