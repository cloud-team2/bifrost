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

CONNECTOR_TARGET_TOOLS = {
    "get_connector_status",
    "get_traces",
    "get_connector_task_trace",
    "restart_connector",
    "pause_connector",
    "resume_connector",
}


def result_from_spring_response(
    *,
    tool_name: str,
    risk: RiskLevel,
    response: SpringOpsResponse,
    requires_approval: bool = False,
    result: dict | list | None = None,
) -> ToolResult:
    evidence_ids = [evidence.evidence_id for evidence in response.evidence]
    if response.ok:
        return ToolResult(
            tool_name=tool_name,
            status=ToolStatus.SUCCESS,
            risk=risk,
            requires_approval=requires_approval,
            summary=_success_summary(response, result),
            result=result,
            evidence_ids=evidence_ids,
            audit_event_id=response.audit_event_id,
            raw_payload=response.model_dump(mode="json"),
        )

    error = response.error or ToolError(
        code=SpringErrorCode.INTERNAL_ERROR,
        message="Spring operation failed without an error body",
    )
    error = _targeted_not_found_error(tool_name, error)
    error = _friendly_tool_error(error)
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
        raw_payload=response.model_dump(mode="json"),
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
        raw_payload={
            "ok": False,
            "tool_name": tool_name,
            "error": {
                "code": getattr(code, "value", code),
                "message": message,
                "retryable": retryable,
                "required_action": required_action,
            },
        },
    )


# 폴백 요약에 값을 노출해도 안전한 categorical scalar 키 (열거형 상태값 — secret 아님).
_SAFE_SCALAR_KEYS = ("status", "state", "action", "severity", "level", "pattern")
# 리소스 식별자 — 사용자 요청 맥락에 이미 존재, raw 로그·secret·connection string 아님.
_SAFE_NAME_KEYS = (
    "connector_name",
    "consumer_group",
    "pipeline_id",
    "incident_id",
    "metric",
)
# 정수 카운트 키 — 값 자체가 집계치라 민감정보 아님.
_SAFE_COUNT_KEYS = (
    "total",
    "match_count",
    "total_lag",
    "related_event_count",
    "count",
    "affected_rows_estimate",
)
_MAX_SUMMARY_PARTS = 4


def _success_summary(response: SpringOpsResponse, result_override: dict | list | None = None) -> str:
    """Spring 응답에서 민감정보 없이 유의미한 성공 요약을 생성.

    우선순위:
    1) 정직성 검증이 필요한 operation 은 구조화 필드에서 재요약한다.
    2) Spring 가 직접 준 ``summary`` 문자열 (기존 동작 유지).
    3) result(dict/list) 의 구조적 요약 — 리스트 항목 수·열거형 상태·집계 카운트만.
       raw 로그·secret·connection string 등 값 덤프는 일절 하지 않음(redaction 보존).
    4) 위 어느 것도 추출 불가하면 기존 ``"{op} completed"`` 안전 폴백.
    """
    base = f"{response.operation} completed"
    result = result_override if result_override is not None else response.result

    if isinstance(result, list):
        return f"{base} ({len(result)} items)"

    if not isinstance(result, dict) or not result:
        return base

    if response.operation == "search_logs":
        return _log_search_summary(base, result)
    if response.operation == "get_recent_changes":
        return _recent_changes_summary(base, result)
    if response.operation == "get_connector_task_trace":
        return _connector_task_trace_summary(base, result)
    summary = result.get("summary")
    if isinstance(summary, str) and summary:
        return summary
    if response.operation == "get_consumer_lag":
        return _consumer_lag_summary(base, result)

    parts: list[str] = []
    # 리스트-값 키: 항목 수만 보고 (내용 미노출).
    for key, value in result.items():
        if isinstance(value, list):
            parts.append(f"{key}: {len(value)}")
    # 안전한 식별자/상태/카운트 스칼라.
    for key in _SAFE_NAME_KEYS + _SAFE_SCALAR_KEYS:
        value = result.get(key)
        if isinstance(value, str) and value:
            parts.append(f"{key}={value}")
    for key in _SAFE_COUNT_KEYS:
        value = result.get(key)
        if isinstance(value, bool):
            continue
        if isinstance(value, int):
            parts.append(f"{key}={value}")

    if not parts:
        return base
    return f"{base} ({', '.join(parts[:_MAX_SUMMARY_PARTS])})"


def _consumer_lag_summary(base: str, result: dict) -> str:
    total_lag = _first_present(result, "total_lag", "totalLag")
    partitions = result.get("partitions")
    top = _first_present(result, "top_lag_partitions", "topLagPartitions")
    p95_lag = _first_present(result, "p95_lag", "p95Lag")
    observed_at = _first_present(result, "observed_at", "observedAt")
    source = result.get("source")

    parts: list[str] = ["consumer lag snapshot"]
    if total_lag is not None:
        parts.append(f"total_lag={total_lag}")
    if isinstance(partitions, list):
        parts.append(f"partition_count={len(partitions)}")
    if p95_lag is not None:
        parts.append(f"lag p95={p95_lag}")
    if isinstance(top, list) and top:
        parts.append(f"top lag partitions={len(top)}")
    if observed_at:
        parts.append(f"observed_at={observed_at}")
    if source:
        parts.append(f"source={source}")
    parts.append("offset position snapshot: current committed offsets and log end offsets captured")
    return f"{base} ({', '.join(parts)})"


def _log_search_summary(base: str, result: dict) -> str:
    total = _first_present(result, "total", "match_count", "matchCount")
    if total is None and isinstance(result.get("logs"), list):
        total = len(result["logs"])
    evidence = result.get("evidence")
    if isinstance(evidence, list) and evidence:
        parts: list[str] = []
        for item in evidence[:4]:
            if not isinstance(item, dict):
                continue
            token = _first_present(item, "matched_required_token", "matchedRequiredToken") or "worker log"
            error_class = _first_present(item, "error_class", "errorClass")
            connector = item.get("connector")
            count = item.get("count")
            detail = str(token)
            if error_class:
                detail += f" class={error_class}"
            if connector:
                detail += f" connector={connector}"
            if isinstance(count, int):
                detail += f" count={count}"
            parts.append(detail)
        if parts:
            return f"{base} ({'; '.join(parts)})"
    return f"{base} (logs={total if total is not None else 0}, structured_matches=0)"


def _recent_changes_summary(base: str, result: dict) -> str:
    changes = result.get("changes")
    if not isinstance(changes, list) or not changes:
        unavailable = _unavailable_sources_part(result)
        return f"{base} (changes=0{unavailable})"
    types: dict[str, int] = {}
    config_evidence = 0
    for change in changes:
        if isinstance(change, dict):
            change_type = str(change.get("type") or "UNKNOWN")
            types[change_type] = types.get(change_type, 0) + 1
            description = str(change.get("description") or "")
            upper_type = change_type.upper()
            if (
                "SNAPSHOT" not in upper_type
                and ("CONFIG" in upper_type or "pipeline/connector config 변경" in description)
            ):
                config_evidence += 1
    config_part = (
        f", 최근 pipeline/connector config 변경 evidence count={config_evidence}"
        if config_evidence
        else ""
    )
    return (
        f"{base} (live change evidence: changes={len(changes)}, types={types}"
        f"{config_part}{_unavailable_sources_part(result)})"
    )


def _connector_task_trace_summary(base: str, result: dict) -> str:
    connector = result.get("connector") or result.get("connector_name") or result.get("connectorName")
    traces = result.get("traces")
    trace_count = len(traces) if isinstance(traces, list) else 0
    if trace_count == 0:
        return f"{base} (connector={connector}, task trace unavailable)"
    failed = 0
    if isinstance(traces, list):
        failed = sum(
            1
            for trace in traces
            if isinstance(trace, dict) and str(trace.get("state", "")).casefold() == "failed"
        )
    if failed == 0:
        return (
            f"{base} (connector task trace available; no failed task confirmed "
            f"connector={connector}, traces={trace_count}, failedTasks=0)"
        )
    return (
        f"{base} (connector task status FAILED; task trace 또는 worker log "
        f"connector={connector}, traces={trace_count}, failedTasks={failed})"
    )


def _unavailable_sources_part(result: dict) -> str:
    unavailable = _first_present(result, "unavailable_sources", "unavailableSources")
    if isinstance(unavailable, list) and unavailable:
        return f", unavailableSources={len(unavailable)}"
    return ""


def _first_present(result: dict, *keys: str):
    for key in keys:
        if key in result:
            return result[key]
    return None


def _is_blocking_error(error: ToolError) -> bool:
    try:
        return SpringErrorCode(error.code) in BLOCKING_ERROR_CODES
    except ValueError:
        return False


def _targeted_not_found_error(tool_name: str, error: ToolError) -> ToolError:
    """Split connector target misses from generic project/path 404s."""
    raw_message = error.message or ""
    lowered = raw_message.lower()
    try:
        code = SpringErrorCode(error.code)
    except ValueError:
        return error

    if (
        tool_name in CONNECTOR_TARGET_TOOLS
        and code == SpringErrorCode.RESOURCE_NOT_FOUND
        and (
            "connector not found" in lowered
            or "커넥터를 찾을 수" in raw_message
        )
    ):
        return error.model_copy(update={"code": SpringErrorCode.CONNECTOR_NOT_FOUND})

    return error


def _friendly_tool_error(error: ToolError) -> ToolError:
    raw_message = error.message or ""
    lowered = raw_message.lower()
    try:
        code = SpringErrorCode(error.code)
    except ValueError:
        code = None

    if (
        code == SpringErrorCode.CONSUMER_GROUP_NOT_FOUND
        or "consumer group is not a kafka connect-managed group" in lowered
    ):
        message = (
            "Kafka Connect가 관리하는 consumer group만 lag 조회할 수 있습니다. "
            "커넥터 이름을 알려주시면 connect-<connectorName> 형식으로 조회하겠습니다."
        )
    elif code == SpringErrorCode.CONNECTOR_NOT_FOUND or "connector not found" in lowered:
        message = (
            "해당 커넥터를 찾을 수 없습니다. "
            "프로젝트의 파이프라인 커넥터 이름을 확인해 주세요."
        )
    elif code == SpringErrorCode.RESOURCE_NOT_OWNED_BY_PROJECT:
        message = "해당 리소스는 현재 프로젝트에 속한 파이프라인 리소스가 아닙니다."
    else:
        return error

    return error.model_copy(update={"message": message})
