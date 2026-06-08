"""Executor — 승인된 ready action을 멱등키로 1회 실행하는 결정론적 단계."""
from __future__ import annotations

import logging
from uuid import uuid4

from app.schemas.outputs import ExecutionResultOutput, ExecutorOutput
from app.schemas.state import ActionCandidate, ActionStatus
from app.schemas.tools import ToolContext, ToolStatus
from app.tools.registry import ToolClientRegistry

logger = logging.getLogger(__name__)

_TERMINAL_MAP = {
    ToolStatus.SUCCESS: ActionStatus.COMPLETED,
    ToolStatus.FAILED: ActionStatus.FAILED,
    ToolStatus.BLOCKED: ActionStatus.BLOCKED,
    ToolStatus.TIMEOUT: ActionStatus.FAILED,
}


async def _snapshot_evidence(action_id: str, phase: str) -> str:
    """before/after 증거 참조 ID 생성 (실제 수집은 evidence 파이프라인에 위임)."""
    return f"evidence://{phase}/{action_id}/{uuid4().hex[:8]}"


async def _after_check(
    action: ActionCandidate,
    context: ToolContext,
    registry: ToolClientRegistry,
) -> ActionStatus:
    """timeout 시 자동 재시도 없이 현재 상태를 한 번 조회해 terminal status 결정."""
    tool_name = action.tool_name
    if not tool_name:
        return ActionStatus.FAILED

    read_tool = f"get_connector_status" if "connector" in tool_name else None
    if not read_tool:
        return ActionStatus.FAILED

    connector_name = action.action_name
    result = await registry.call_tool(
        read_tool,
        {"connector_name": connector_name},
        context,
    )
    if result.status == ToolStatus.SUCCESS:
        return ActionStatus.COMPLETED
    return ActionStatus.FAILED


async def run_executor(
    candidates: list[ActionCandidate],
    *,
    run_id: str,
    context: ToolContext,
    registry: ToolClientRegistry,
) -> ExecutorOutput:
    """ready action만 멱등키로 실행. ready 아닌 action은 건너뜀(중복 실행 차단)."""
    results: list[ExecutionResultOutput] = []

    for action in candidates:
        if action.status != ActionStatus.READY:
            continue
        if not action.tool_name:
            logger.warning("executor: action %s has no tool_name, skipping", action.action_id)
            continue

        before_ref = await _snapshot_evidence(action.action_id, "before")
        idempotency_key = f"{run_id}:{action.action_id}"
        exec_context = context.with_idempotency_key(idempotency_key)

        tool_result = await registry.call_tool(
            action.tool_name,
            {},
            exec_context,
        )

        timed_out = tool_result.status == ToolStatus.TIMEOUT
        status = _TERMINAL_MAP.get(tool_result.status, ActionStatus.FAILED)

        if timed_out:
            status = await _after_check(action, context, registry)

        after_ref = await _snapshot_evidence(action.action_id, "after")

        results.append(ExecutionResultOutput(
            action_id=action.action_id,
            tool_name=action.tool_name,
            status=status,
            audit_event_id=tool_result.audit_event_id,
            before_evidence_id=before_ref,
            after_evidence_id=after_ref,
            reason_code=tool_result.error.code if tool_result.error else None,
            summary=tool_result.summary,
        ))

    return ExecutorOutput(execution_results=results)
