"""Rollback — 조치 실패 시 사람 개입 없이 원상복구를 실행하는 결정론적 단계(#886).

설계문서 §7-5 / §Q2: Executor 가 실행한 mutation 이 성공 조건을 충족하지 못하면
(status FAILED/BLOCKED) inverse 조치를 자동 실행해 직전 상태로 되돌린다. risk-tier
정책에 따라 low/medium 원조치는 자동 롤백하고, high 원조치는 롤백 실행도 사람 승인
대상으로 남긴다(PENDING_APPROVAL). 모든 롤백은 멱등키와 감사 이벤트로 추적된다.
"""
from __future__ import annotations

import logging
from uuid import uuid4

from app.schemas.outputs import (
    ExecutionResultOutput,
    RollbackOutput,
    RollbackResultOutput,
)
from app.schemas.state import ActionCandidate, ActionStatus, RiskLevel, RollbackStatus
from app.schemas.tools import ToolContext, ToolStatus
from app.tools.registry import ToolClientRegistry

logger = logging.getLogger(__name__)

# 성공 조건 미달로 보는 실행 상태 — 이 경우 자동 롤백을 시도한다.
_FAILED_STATUSES = {ActionStatus.FAILED, ActionStatus.BLOCKED}

# 직접 대칭 inverse 가 있는 mutation. pause↔resume 는 서로의 원복이다.
_INVERSE_TOOL = {
    "pause_connector": "resume_connector",
    "resume_connector": "pause_connector",
}

# 원조치 risk 가 이 이하면 자동 롤백, 초과(high/forbidden)면 승인 대상.
_AUTO_ROLLBACK_CEILING_ORDER = {
    RiskLevel.READ_ONLY: 0,
    RiskLevel.LOW: 1,
    RiskLevel.MEDIUM: 2,
    RiskLevel.HIGH: 3,
    RiskLevel.FORBIDDEN: 4,
}
_AUTO_ROLLBACK_MAX = _AUTO_ROLLBACK_CEILING_ORDER[RiskLevel.MEDIUM]


def _original_tool_risk(tool_name: str | None, registry: ToolClientRegistry) -> RiskLevel:
    if not tool_name:
        return RiskLevel.HIGH
    definition = registry.get_definition(tool_name)
    return definition.risk if definition is not None else RiskLevel.HIGH


def _snapshot_state(pre_change_snapshot: str | None) -> str | None:
    """snapshot://before/{action_id}/{state} 에서 직전 상태값을 추출."""
    if not pre_change_snapshot:
        return None
    return pre_change_snapshot.rsplit("/", 1)[-1] or None


def _rollback_plan(
    action: ActionCandidate, pre_change_snapshot: str | None
) -> tuple[str, dict] | None:
    """원조치 → inverse 롤백 (tool_name, params). 되돌릴 수 없으면 None."""
    tool_name = action.tool_name or ""
    params = dict(action.tool_params or {})

    inverse = _INVERSE_TOOL.get(tool_name)
    if inverse is not None:
        return inverse, params

    if tool_name == "restart_connector":
        # 재시작 실패는 직전 상태로 복원한다. RUNNING 이었다면 resume, PAUSED 였다면 pause.
        state = (_snapshot_state(pre_change_snapshot) or "").lower()
        if state in {"running", "active"}:
            return "resume_connector", params
        if state in {"paused"}:
            return "pause_connector", params
        return None

    # restart_consumer_group 등은 안전한 inverse 가 없다.
    return None


async def run_rollback(
    execution_results: list[ExecutionResultOutput],
    candidates: list[ActionCandidate],
    *,
    run_id: str,
    context: ToolContext,
    registry: ToolClientRegistry,
) -> RollbackOutput:
    """실패한 mutation 마다 inverse 조치를 risk-tier 정책에 따라 자동 실행한다."""
    by_action = {c.action_id: c for c in candidates}
    results: list[RollbackResultOutput] = []

    for execution in execution_results:
        if execution.status not in _FAILED_STATUSES:
            continue
        action = by_action.get(execution.action_id)
        if action is None:
            continue

        rollback_action_id = f"rollback_{execution.action_id}"
        plan = _rollback_plan(action, execution.pre_change_snapshot)
        if plan is None:
            results.append(RollbackResultOutput(
                rollback_action_id=rollback_action_id,
                original_action_id=execution.action_id,
                rollback_status=RollbackStatus.NOT_APPLICABLE,
                tool_name=None,
                pre_change_snapshot=execution.pre_change_snapshot,
                summary=f"{action.tool_name} 은 안전하게 되돌릴 inverse 조치가 없어 자동 롤백을 건너뜁니다.",
            ))
            continue

        rollback_tool, rollback_params = plan
        original_risk = _original_tool_risk(action.tool_name, registry)

        # high/forbidden 원조치는 롤백 실행도 사람 승인 대상으로 남긴다.
        if _AUTO_ROLLBACK_CEILING_ORDER[original_risk] > _AUTO_ROLLBACK_MAX:
            results.append(RollbackResultOutput(
                rollback_action_id=rollback_action_id,
                original_action_id=execution.action_id,
                rollback_status=RollbackStatus.PENDING_APPROVAL,
                tool_name=rollback_tool,
                tool_params=rollback_params,
                pre_change_snapshot=execution.pre_change_snapshot,
                summary=(
                    f"high-risk 조치({action.tool_name}) 실패 — 롤백 {rollback_tool} 은 "
                    "승인 후 실행 대상으로 보류합니다."
                ),
            ))
            continue

        # low/medium 원조치는 자동 롤백한다. 멱등키로 1회만, 자동 롤백 승인 컨텍스트로 호출.
        rollback_context = context.with_idempotency_key(
            f"{run_id}:rollback:{execution.action_id}"
        ).with_approval(approval_id=f"auto_rollback_{execution.action_id}")
        try:
            tool_result = await registry.call_tool(rollback_tool, rollback_params, rollback_context)
        except Exception as exc:
            logger.warning("rollback call failed action=%s error=%s", execution.action_id, exc)
            results.append(RollbackResultOutput(
                rollback_action_id=rollback_action_id,
                original_action_id=execution.action_id,
                rollback_status=RollbackStatus.FAILED,
                tool_name=rollback_tool,
                tool_params=rollback_params,
                pre_change_snapshot=execution.pre_change_snapshot,
                summary=f"롤백 {rollback_tool} 호출이 예외로 실패했습니다.",
            ))
            continue

        succeeded = tool_result.status == ToolStatus.SUCCESS
        results.append(RollbackResultOutput(
            rollback_action_id=rollback_action_id,
            original_action_id=execution.action_id,
            rollback_status=RollbackStatus.COMPLETED if succeeded else RollbackStatus.FAILED,
            tool_name=rollback_tool,
            tool_params=rollback_params,
            rollback_audit_event_id=tool_result.audit_event_id or f"rbk_{uuid4().hex[:8]}",
            pre_change_snapshot=execution.pre_change_snapshot,
            summary=(
                f"조치 실패로 {rollback_tool} 자동 롤백 "
                f"{'성공' if succeeded else '실패'}: {tool_result.summary}"
            ),
        ))

    return RollbackOutput(rollback_results=results)
