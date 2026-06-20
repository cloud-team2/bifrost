"""Stage transition tables per AgentMode (§15 §4 Branch 규칙).

#882 자연어 질의 실행 깊이 제어: simple_query 는 ExecutionDepth 에 따라 더 짧은
stage 경로를 탄다(verifier 제거 등). incident/action 경로는 기존 mode 표를 따른다.
"""
from __future__ import annotations

from app.schemas.state import AgentMode, ExecutionDepth, HistoryPolicy

# 기존 simple_query 표(depth 미지정 호환). verifier 포함.
SIMPLE_QUERY_STAGES: tuple[str, ...] = ("planner", "retrieval", "verifier", "report")

# #882 direct/single/bounded lookup — 단순 조회는 검증 agent 없이 짧게 끝낸다.
# Verifier 는 운영 변경·RCA 결론처럼 검증 가치가 큰 출력에만 둔다(설계 §6.4.2).
SIMPLE_QUERY_LOOKUP_STAGES: tuple[str, ...] = ("planner", "retrieval", "report")

INCIDENT_ANALYSIS_STAGES: tuple[str, ...] = (
    "correlation", "planner", "retrieval",
    "classifier", "rca", "verifier", "report",
)

INCIDENT_ANALYSIS_REMEDIATION_SUFFIX: tuple[str, ...] = (
    "remediation",
    "policy_guard",
    "approval_gate",
)

ACTION_EXECUTION_STAGES: tuple[str, ...] = (
    "policy_guard", "approval_gate", "change_gate",
    "executor", "verifier", "report",
)

APPROVAL_DECISION_STAGES: tuple[str, ...] = (
    "approval_gate", "executor", "verifier", "report",
)

# simple_query 의 짧은 경로를 쓰는 depth 집합.
_LOOKUP_DEPTHS = frozenset(
    {
        ExecutionDepth.DIRECT_ANSWER,
        ExecutionDepth.SINGLE_LOOKUP,
        ExecutionDepth.BOUNDED_LOOKUP,
    }
)


def default_depth_for_mode(
    mode: AgentMode, remediation_requested: bool = False
) -> ExecutionDepth:
    """mode(+remediation) 만으로 합리적 기본 depth 를 정한다(Router 미분류 대비)."""
    if mode == AgentMode.SIMPLE_QUERY:
        return ExecutionDepth.BOUNDED_LOOKUP
    if mode == AgentMode.INCIDENT_ANALYSIS:
        return (
            ExecutionDepth.REMEDIATION_PLANNING
            if remediation_requested
            else ExecutionDepth.INCIDENT_DIAGNOSIS
        )
    # action_execution / approval_decision
    return ExecutionDepth.ACTION_EXECUTION


# depth → (max_tool_calls, allow_react_loop, react_max_steps, history_policy)
# 단순 조회는 read-only tool 수를 좁히고 ReAct 루프를 끈다(의도치 않은 다중 호출 차단).
_DEPTH_BUDGET: dict[ExecutionDepth, tuple[int, bool, int, HistoryPolicy]] = {
    ExecutionDepth.DIRECT_ANSWER: (0, False, 0, HistoryPolicy.NONE),
    ExecutionDepth.SINGLE_LOOKUP: (1, False, 0, HistoryPolicy.NONE),
    ExecutionDepth.BOUNDED_LOOKUP: (2, False, 2, HistoryPolicy.SUMMARY),
    ExecutionDepth.INCIDENT_DIAGNOSIS: (6, True, 6, HistoryPolicy.SUMMARY),
    ExecutionDepth.REMEDIATION_PLANNING: (6, True, 6, HistoryPolicy.SUMMARY),
    ExecutionDepth.ACTION_EXECUTION: (0, False, 0, HistoryPolicy.SUMMARY),
}


def depth_budget(depth: ExecutionDepth) -> tuple[int, bool, int, HistoryPolicy]:
    """depth 별 (max_tool_calls, allow_react_loop, react_max_steps, history_policy)."""
    return _DEPTH_BUDGET[depth]


def stages_for_mode(
    mode: AgentMode,
    remediation_requested: bool = False,
    execution_depth: ExecutionDepth | None = None,
) -> tuple[str, ...]:
    if mode == AgentMode.SIMPLE_QUERY:
        if execution_depth in _LOOKUP_DEPTHS:
            return SIMPLE_QUERY_LOOKUP_STAGES
        return SIMPLE_QUERY_STAGES
    if mode == AgentMode.INCIDENT_ANALYSIS:
        base = INCIDENT_ANALYSIS_STAGES
        # depth=remediation_planning 도 remediation suffix 를 켠다(요청 플래그와 동치).
        wants_remediation = remediation_requested or (
            execution_depth == ExecutionDepth.REMEDIATION_PLANNING
        )
        if wants_remediation:
            idx = base.index("rca") + 1
            return base[:idx] + INCIDENT_ANALYSIS_REMEDIATION_SUFFIX + base[idx:]
        return base
    if mode == AgentMode.ACTION_EXECUTION:
        return ACTION_EXECUTION_STAGES
    if mode == AgentMode.APPROVAL_DECISION:
        return APPROVAL_DECISION_STAGES
    return ()


def next_stage(
    mode: AgentMode,
    current: str | None,
    remediation_requested: bool = False,
    execution_depth: ExecutionDepth | None = None,
) -> str | None:
    stages = stages_for_mode(mode, remediation_requested, execution_depth)
    if not stages:
        return None
    if current is None:
        return stages[0]
    try:
        idx = stages.index(current)
    except ValueError:
        return None
    next_idx = idx + 1
    return stages[next_idx] if next_idx < len(stages) else None
