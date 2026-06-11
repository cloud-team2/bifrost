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

    params = _build_tool_params(tool_name, action.action_name, action.tool_params)
    connector_name = params.get("connector_name")
    if not isinstance(connector_name, str) or not connector_name:
        return ActionStatus.FAILED
    result = await registry.call_tool(
        read_tool,
        {"connector_name": connector_name},
        context,
    )
    if result.status == ToolStatus.SUCCESS:
        return ActionStatus.COMPLETED
    return ActionStatus.FAILED


def _build_tool_params(tool_name: str, action_name: str, tool_params: dict | None = None) -> dict:
    """tool_name 패턴으로 필요한 path param을 action_name에서 추출."""
    if tool_params:
        return dict(tool_params)
    if "connector" in tool_name:
        return {"connector_name": action_name}
    if "consumer_group" in tool_name:
        return {"consumer_group": action_name}
    return {}


async def run_executor(
    candidates: list[ActionCandidate],
    *,
    run_id: str,
    context: ToolContext,
    registry: ToolClientRegistry,
    approval_by_action: dict[str, str] | None = None,
    change_ticket_by_action: dict[str, str] | None = None,
) -> ExecutorOutput:
    """ready action만 멱등키로 실행. ready 아닌 action은 건너뜀(중복 실행 차단).

    approval_by_action / change_ticket_by_action 은 action_id → governance 식별자
    매핑이다. 승인이 필요한 mutation 실행 시 해당 action 의 approval_id(+change_ticket_id)
    를 ToolContext 에 실어 Spring governance gate(X-Approval-Id / X-Change-Ticket-Id)
    와 연결한다. read tool 은 매핑이 없으므로 자연히 미전송. (#475)
    """
    results: list[ExecutionResultOutput] = []
    approval_by_action = approval_by_action or {}
    change_ticket_by_action = change_ticket_by_action or {}

    for action in candidates:
        if action.status != ActionStatus.READY:
            continue
        if not action.tool_name:
            logger.warning("executor: action %s has no tool_name, skipping", action.action_id)
            continue

        before_ref = await _snapshot_evidence(action.action_id, "before")
        idempotency_key = f"{run_id}:{action.action_id}"
        exec_context = context.with_idempotency_key(idempotency_key).with_approval(
            approval_id=approval_by_action.get(action.action_id),
            change_ticket_id=change_ticket_by_action.get(action.action_id),
        )

        tool_result = await registry.call_tool(
            action.tool_name,
            _build_tool_params(action.tool_name, action.action_name, action.tool_params),
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
