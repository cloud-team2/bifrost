"""Supervisor — workflow controller (§15 Workflow Control).

Orchestrates stage sequencing, enforces loop guards, and manages AgentState.
Does NOT contain LLM logic; actual agent execution is delegated in later issues.
"""
from __future__ import annotations

from datetime import datetime, timezone

from app.schemas.state import (
    AgentMode,
    AgentState,
    ExecutionDepth,
    RunStatus,
    VerificationStatus,
)
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
        # #882 Router 가 정한 실행 깊이. depth-aware stage 선택에 쓴다.
        self._execution_depth: dict[str, ExecutionDepth] = {}
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
        execution_depth: ExecutionDepth | None = None,
    ) -> AgentState:
        self._remediation_flags[run_id] = remediation_requested
        if execution_depth is not None:
            self._execution_depth[run_id] = execution_depth
        state = self._store.create(run_id, mode, incident_id)
        # #481: wall-clock 예산 기준점. stage_started_at은 첫 advance에서 설정된다.
        now = datetime.now(timezone.utc)
        self._store.patch(run_id, lambda s: _mark_started(s, now))
        return state

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
            execution_depth = self._execution_depth.get(run_id)
            nxt = next_stage(mode, current, remediation_requested, execution_depth)

        stage_start = datetime.now(timezone.utc)

        def _apply(s: AgentState) -> AgentState:
            s.run.step_count += 1
            s.run.current_agent = nxt
            # #481: 새 stage 진입 시각을 기록한다. 다음 advance에서 check_stage_timeout이
            # 직전 stage의 체류 시간을 이 값 기준으로 검사한다.
            s.run.stage_started_at = stage_start
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

    def force_next_stage(self, run_id: str, next_agent: str) -> None:
        """다음 advance에서 정적 테이블 대신 지정 stage로 진행한다(#592).

        no-progress 게이트가 재수집을 생략하고 기존 evidence로 rca→remediation을
        이어갈 때 사용하는 단발 전방 점프. loopback 예산 카운터를 소비하지 않으며,
        전체 흐름의 유한 종료는 step 예산(check_all_global)이 보장한다.
        """
        if self._store.get(run_id) is None:
            raise KeyError(f"Run not found: {run_id}")
        self._pending_loopback[run_id] = next_agent

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

    def record_llm_usage(self, run_id: str, *, calls: int = 1, tokens: int = 0) -> None:
        """LLM 호출/토큰 사용량을 run state에 누적한다(#481).

        누적된 ``llm_call_count``·``token_count``는 다음 advance의 check_all_global이
        ``max_llm_calls``·``max_tokens`` 예산과 비교해 초과 시 run을 안전 종료한다.
        run을 찾지 못하면 무시한다(계측 실패가 흐름을 막지 않도록).
        """
        if self._store.get(run_id) is None:
            return
        self._store.patch(run_id, lambda s: _bump_usage(s, calls, tokens))


def _bump_usage(state: AgentState, calls: int, tokens: int) -> AgentState:
    state.run.llm_call_count += max(0, calls)
    state.run.token_count += max(0, tokens)
    return state


def _mark_started(state: AgentState, now: datetime) -> AgentState:
    state.run.started_at = now
    return state


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
