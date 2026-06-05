"""Tool result normalization helpers."""
from __future__ import annotations

from app.schemas.state import RiskLevel
from app.schemas.tools import SpringErrorCode, SpringOpsResponse, ToolError, ToolResult, ToolStatus

BLOCKING_ERROR_CODES = {
    SpringErrorCode.POLICY_DENIED,
    SpringErrorCode.APPROVAL_REQUIRED,
    SpringErrorCode.CHANGE_TICKET_REQUIRED,
    SpringErrorCode.CHANGE_WINDOW_CLOSED,
    SpringErrorCode.APPROVAL_EXPIRED,
    SpringErrorCode.APPROVAL_SCOPE_MISMATCH,
    SpringErrorCode.CHANGE_SCOPE_MISMATCH,
}


def result_from_spring_response(
    *,
    tool_name: str,
    risk: RiskLevel,
    response: SpringOpsResponse,
    requires_approval: bool = False,
) -> ToolResult:
    evidence_ids = [evidence.evidence_id for evidence in response.evidence]
    if response.ok:
        return ToolResult(
            tool_name=tool_name,
            status=ToolStatus.SUCCESS,
            risk=risk,
            requires_approval=requires_approval,
            summary=_success_summary(response),
            evidence_ids=evidence_ids,
            audit_event_id=response.audit_event_id,
        )

    error = response.error or ToolError(
        code=SpringErrorCode.INTERNAL_ERROR,
        message="Spring operation failed without an error body",
    )
    status = ToolStatus.BLOCKED if _is_blocking_error(error) else ToolStatus.FAILED
    return ToolResult(
        tool_name=tool_name,
        status=status,
        risk=risk,
        requires_approval=requires_approval or status == ToolStatus.BLOCKED,
        summary=error.message,
        evidence_ids=evidence_ids,
        audit_event_id=response.audit_event_id,
        error=error,
    )


def failed_tool_result(
    *,
    tool_name: str,
    risk: RiskLevel,
    code: SpringErrorCode | str,
    message: str,
    status: ToolStatus = ToolStatus.FAILED,
    retryable: bool = False,
    required_action: str | None = None,
) -> ToolResult:
    return ToolResult(
        tool_name=tool_name,
        status=status,
        risk=risk,
        requires_approval=status == ToolStatus.BLOCKED,
        summary=message,
        error=ToolError(
            code=code,
            message=message,
            retryable=retryable,
            required_action=required_action,
        ),
    )


def _success_summary(response: SpringOpsResponse) -> str:
    if isinstance(response.result, dict):
        summary = response.result.get("summary")
        if isinstance(summary, str) and summary:
            return summary
    return f"{response.operation} completed"


def _is_blocking_error(error: ToolError) -> bool:
    try:
        return SpringErrorCode(error.code) in BLOCKING_ERROR_CODES
    except ValueError:
        return False
