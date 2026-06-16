"""Tests for tool result normalization, focusing on _success_summary fallback (#469)."""
from __future__ import annotations

from app.schemas.state import RiskLevel
from app.schemas.tools import (
    SpringErrorCode,
    SpringOpsResponse,
    ToolError,
    ToolStatus,
)
from app.tools.result import result_from_spring_response


def _ok_response(result, operation: str = "search_logs") -> SpringOpsResponse:
    return SpringOpsResponse(
        ok=True,
        request_id="req-1",
        operation=operation,
        result=result,
    )


def _summary_for(result, operation: str = "search_logs") -> str:
    tool_result = result_from_spring_response(
        tool_name="t",
        risk=RiskLevel.LOW,
        response=_ok_response(result, operation=operation),
    )
    assert tool_result.status == ToolStatus.SUCCESS
    return tool_result.summary


def test_explicit_summary_preserved() -> None:
    """summary 필드가 있으면 그 값을 그대로 사용 (기존 동작 유지)."""
    summary = _summary_for({"summary": "12 matching log lines"})
    assert summary == "12 matching log lines"


def test_top_level_list_reports_count() -> None:
    """result 가 raw list 면 항목 수를 포함한 요약."""
    summary = _summary_for([{"id": 1}, {"id": 2}, {"id": 3}], operation="get_changes")
    assert summary == "get_changes completed (3 items)"


def test_dict_list_key_reports_count_without_dumping_contents() -> None:
    """list-값 키는 항목 수만 노출하고 raw 내용은 노출하지 않는다."""
    secret_log = {"message": "DB password=hunter2", "host": "10.0.0.1"}
    summary = _summary_for(
        {"logs": [secret_log, secret_log]},
        operation="search_logs",
    )
    assert summary == "search_logs completed (logs: 2)"
    assert "hunter2" not in summary
    assert "password" not in summary


def test_status_and_count_scalars_included() -> None:
    """안전한 상태/카운트 스칼라는 요약에 포함된다."""
    summary = _summary_for(
        {"connector_name": "pg-source", "state": "RUNNING", "tasks": [{}, {}]},
        operation="get_connector_status",
    )
    assert summary.startswith("get_connector_status completed (")
    assert "tasks: 2" in summary
    assert "connector_name=pg-source" in summary
    assert "state=RUNNING" in summary


def test_count_field_included() -> None:
    summary = _summary_for({"total": 42}, operation="search_logs")
    assert summary == "search_logs completed (total=42)"


def test_empty_dict_falls_back() -> None:
    """빈 result 는 안전 폴백."""
    assert _summary_for({}, operation="restart_connector") == "restart_connector completed"


def test_none_result_falls_back() -> None:
    """result 가 None 이면 안전 폴백."""
    assert _summary_for(None, operation="restart_connector") == "restart_connector completed"


def test_dict_without_known_keys_falls_back() -> None:
    """추출 가능한 안전 필드가 없으면 값 덤프 없이 안전 폴백."""
    summary = _summary_for(
        {"connectionString": "postgres://user:pw@host/db"},
        operation="describe_connector",
    )
    assert summary == "describe_connector completed"
    assert "postgres://" not in summary
    assert "pw@host" not in summary


def test_empty_list_falls_back_to_zero_items() -> None:
    assert _summary_for([], operation="get_changes") == "get_changes completed (0 items)"


def test_failed_response_uses_error_message() -> None:
    """실패 응답은 에러 메시지를 summary 로 사용 (회귀 방지)."""
    response = SpringOpsResponse(
        ok=False,
        request_id="req-2",
        operation="restart_connector",
        error=ToolError(code=SpringErrorCode.POLICY_DENIED, message="not allowed"),
    )
    tool_result = result_from_spring_response(
        tool_name="t",
        risk=RiskLevel.HIGH,
        response=response,
    )
    assert tool_result.status == ToolStatus.BLOCKED
    assert tool_result.summary == "not allowed"


def test_connect_consumer_group_policy_error_is_user_friendly() -> None:
    response = SpringOpsResponse(
        ok=False,
        request_id="req-3",
        operation="get_consumer_lag",
        error=ToolError(
            code=SpringErrorCode.POLICY_DENIED,
            message="consumer group is not a Kafka Connect-managed group: default-group",
        ),
    )

    tool_result = result_from_spring_response(
        tool_name="get_consumer_lag",
        risk=RiskLevel.READ_ONLY,
        response=response,
    )

    assert tool_result.status == ToolStatus.BLOCKED
    assert "Kafka Connect가 관리하는 consumer group" in tool_result.summary
    assert "default-group" not in tool_result.summary
    assert tool_result.error is not None
    assert "default-group" not in tool_result.error.message


def test_connector_not_found_error_is_user_friendly() -> None:
    response = SpringOpsResponse(
        ok=False,
        request_id="req-4",
        operation="get_connector_status",
        error=ToolError(
            code=SpringErrorCode.CONNECTOR_NOT_FOUND,
            message="connector not found: default-connector",
        ),
    )

    tool_result = result_from_spring_response(
        tool_name="get_connector_status",
        risk=RiskLevel.READ_ONLY,
        response=response,
    )

    assert "커넥터를 찾을 수 없습니다" in tool_result.summary
    assert "default-connector" not in tool_result.summary


def test_connector_resource_not_found_is_targeted_reason_code() -> None:
    response = SpringOpsResponse(
        ok=False,
        request_id="req-5",
        operation="get_connector_status",
        error=ToolError(
            code=SpringErrorCode.RESOURCE_NOT_FOUND,
            message="connector not found: missing-source",
            required_action="check_project_scope",
        ),
    )

    tool_result = result_from_spring_response(
        tool_name="get_connector_status",
        risk=RiskLevel.READ_ONLY,
        response=response,
    )

    assert tool_result.error is not None
    assert tool_result.error.code == SpringErrorCode.CONNECTOR_NOT_FOUND
    assert "커넥터를 찾을 수 없습니다" in tool_result.summary
    assert "missing-source" not in tool_result.summary
