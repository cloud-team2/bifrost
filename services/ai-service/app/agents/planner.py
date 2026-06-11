"""Planner agent — keyword-based tool selection (no LLM).

Tool names follow tool-catalog.md §8 Read-only Runtime Tool Catalog.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any

from app.schemas.outputs import PlannerOutput, RetrievalPlanStep
from app.schemas.tools import ToolContext, ToolStatus
from app.tools.registry import ToolClientRegistry

_LOG_PARAMS = {"query": "pipeline events", "time_range": {"from": "now-30m", "to": "now"}}
_INCIDENT_LOG_PARAMS = {"query": "error exception failure", "time_range": {"from": "now-1h", "to": "now"}}
# list_project_pipelines (operation: list_project_pipelines) takes no params — ListProjectPipelinesParams is empty.
_PIPELINE_LIST_PARAMS: dict = {}

_KEYWORD_TOOL_MAP: list[tuple[set[str], str, dict]] = [
    # catalog §8.4 Pipeline read — list_project_pipelines (operation: list_project_pipelines)
    # "파이프라인 리스트/목록/연결/현황/상태" 의도는 로그 검색이 아니라 파이프라인 목록 조회다 (#468).
    ({"파이프라인", "pipeline", "리스트", "목록", "연결", "현황", "상태"}, "list_project_pipelines", _PIPELINE_LIST_PARAMS),
    # 로그 검색 의도(로그/log)만 search_logs 로 유지; 파이프라인 키워드는 위 버킷으로 분리.
    ({"로그", "log"}, "search_logs", _LOG_PARAMS),
    # catalog §8.1 Observability — get_metrics (operation: query_metrics)
    ({"메트릭", "metric", "지표", "수치", "성능"}, "get_metrics", {"metric": "pipeline_lag_seconds", "time_range": "last_30m"}),
    # catalog §8.2 Pipeline / Change — get_deployments (operation: get_recent_changes)
    ({"배포", "deploy", "변경", "change", "토폴로지", "topology"}, "get_deployments", {}),
    # catalog §8.3 Kafka Connect — get_connector_status
    ({"커넥터", "connector"}, "get_connector_status", {}),
    # catalog §8.3 Kafka Consumer — get_consumer_lag
    ({"lag", "컨슈머", "consumer", "지연"}, "get_consumer_lag", {}),
    # incident/장애 → log search for error evidence
    ({"인시던트", "incident", "장애", "오류", "에러"}, "search_logs", _INCIDENT_LOG_PARAMS),
    # tool-catalog §8.1 Observability — get_traces (Tempo 분산 trace, #373): source→sink 어디서 지연/실패했나
    ({"trace", "span", "latency", "분산추적", "지연추적"}, "get_traces", {}),
    # tool-catalog §8.1 Observability — get_connector_task_trace (#368/#373): 커넥터 task 예외 stack trace
    ({"스택", "스택트레이스", "stacktrace", "예외", "exception"}, "get_connector_task_trace", {}),
    # tool-catalog §8.1 Observability — get_alerts
    ({"alert", "알람", "알럿", "경보"}, "get_alerts", {}),
]
_DEFAULT_TOOL = ("search_logs", _LOG_PARAMS)
_CONNECTOR_PARAM_TOOLS = frozenset({"get_connector_status", "get_traces", "get_connector_task_trace"})
_CONSUMER_GROUP_PARAM_TOOLS = frozenset({"get_consumer_lag", "get_kafka_lag"})
_IDENTIFIER_STOPWORDS = {
    "connector",
    "connectors",
    "consumer",
    "group",
    "groups",
    "lag",
    "trace",
    "span",
    "latency",
    "status",
    "state",
    "task",
    "stacktrace",
    "exception",
    "default-connector",
    "default-group",
}
_IDENTIFIER_RE = r"[A-Za-z0-9][A-Za-z0-9._:-]*"


@dataclass(frozen=True)
class _Identifier:
    value: str
    kind: str  # connector | consumer_group


def _plan_hash(tool_name: str, params: dict) -> str:
    canonical = json.dumps({"tool_name": tool_name, "params": params}, sort_keys=True)
    return hashlib.sha256(canonical.encode()).hexdigest()[:16]


async def run_planner(
    user_message: str,
    project_id: str,
    *,
    registry: ToolClientRegistry | None = None,
    tool_context: ToolContext | None = None,
) -> PlannerOutput:
    msg = user_message.lower()
    selected: list[tuple[str, dict]] = []

    seen_tools: set[str] = set()
    for keywords, tool, params in _KEYWORD_TOOL_MAP:
        if tool in seen_tools:
            # 한 tool 은 한 번만 호출 — 같은 도구에 대한 중복 step 방지 (#468).
            continue
        if any(kw in msg for kw in keywords):
            selected.append((tool, params))
            seen_tools.add(tool)

    if not selected:
        selected.append(_DEFAULT_TOOL)

    selected_tools = {tool for tool, _ in selected}
    if selected_tools & (_CONNECTOR_PARAM_TOOLS | _CONSUMER_GROUP_PARAM_TOOLS):
        identifier = _extract_identifier(user_message)
        if identifier is None and registry is not None and tool_context is not None:
            identifier = await _resolve_single_project_connector(registry, tool_context)

        if identifier is None:
            return PlannerOutput(
                retrieval_plan=[],
                clarification_message=_identifier_required_message(selected_tools),
            )

        selected = [
            (tool, _params_with_identifier(tool, params, identifier))
            for tool, params in selected
        ]

    steps = [
        RetrievalPlanStep(
            step_id=f"step_{i + 1:03d}",
            tool_name=tool,
            params=params,
            purpose=f"{tool} 조회",
            required=True,
            depends_on=[],
            plan_hash=_plan_hash(tool, params),
        )
        for i, (tool, params) in enumerate(selected)
    ]

    return PlannerOutput(retrieval_plan=steps)


def _params_with_identifier(tool: str, params: dict, identifier: _Identifier) -> dict:
    next_params = dict(params)
    if tool in _CONNECTOR_PARAM_TOOLS:
        connector_name = identifier.value
        if identifier.kind == "consumer_group" and connector_name.lower().startswith("connect-"):
            connector_name = connector_name[len("connect-"):]
        next_params["connector_name"] = connector_name
    elif tool in _CONSUMER_GROUP_PARAM_TOOLS:
        if identifier.kind == "consumer_group" or identifier.value.lower().startswith("connect-"):
            next_params["consumer_group"] = identifier.value
        else:
            next_params["consumer_group"] = f"connect-{identifier.value}"
    return next_params


def _extract_identifier(user_message: str) -> _Identifier | None:
    quoted = re.search(r"`([^`]+)`|['\"]([^'\"]+)['\"]", user_message)
    if quoted:
        value = (quoted.group(1) or quoted.group(2)).strip()
        if _is_identifier(value):
            return _Identifier(value=value, kind=_kind_for(value))

    group_match = re.search(rf"\b(connect-{_IDENTIFIER_RE})\b", user_message, flags=re.IGNORECASE)
    if group_match:
        return _Identifier(value=group_match.group(1), kind="consumer_group")

    patterns = [
        rf"(?:connector|커넥터)\s*(?:name|이름|명)?\s*[:=]?\s*({_IDENTIFIER_RE})",
        rf"({_IDENTIFIER_RE})\s*(?:connector|커넥터)",
        rf"({_IDENTIFIER_RE})\s*(?:consumer|컨슈머)",
        rf"(?:consumer\s*group|컨슈머\s*그룹|group|그룹)\s*(?:name|이름|명)?\s*[:=]?\s*({_IDENTIFIER_RE})",
        rf"({_IDENTIFIER_RE})\s*(?:lag|지연)",
    ]
    for pattern in patterns:
        match = re.search(pattern, user_message, flags=re.IGNORECASE)
        if not match:
            continue
        value = match.group(1)
        if _is_identifier(value):
            return _Identifier(value=value, kind=_kind_for(value))

    return None


def _is_identifier(value: str) -> bool:
    normalized = value.strip().lower()
    return bool(re.fullmatch(_IDENTIFIER_RE, value.strip())) and normalized not in _IDENTIFIER_STOPWORDS


def _kind_for(value: str) -> str:
    return "consumer_group" if value.lower().startswith("connect-") else "connector"


async def _resolve_single_project_connector(
    registry: ToolClientRegistry,
    context: ToolContext,
) -> _Identifier | None:
    call_with_data = getattr(registry, "call_tool_with_data", None)
    if call_with_data is None:
        return None

    pipelines_result, pipelines_data = await call_with_data("list_project_pipelines", {}, context)
    if pipelines_result.status != ToolStatus.SUCCESS:
        return None

    pipeline_ids = _pipeline_ids(pipelines_data)
    connector_names: set[str] = set()
    for pipeline_id in pipeline_ids:
        topology_result, topology_data = await call_with_data(
            "get_pipeline_topology",
            {"pipeline_id": pipeline_id},
            context,
        )
        if topology_result.status != ToolStatus.SUCCESS:
            continue
        connector_names.update(_connector_names(topology_data))

    if len(connector_names) != 1:
        return None
    return _Identifier(value=next(iter(connector_names)), kind="connector")


def _pipeline_ids(data: Any) -> list[str]:
    pipelines = data.get("pipelines", []) if isinstance(data, dict) else data
    if not isinstance(pipelines, list):
        return []

    ids: list[str] = []
    for pipeline in pipelines:
        if not isinstance(pipeline, dict):
            continue
        pipeline_id = pipeline.get("pipeline_id") or pipeline.get("pipelineId") or pipeline.get("id")
        if isinstance(pipeline_id, str) and pipeline_id:
            ids.append(pipeline_id)
    return ids


def _connector_names(data: Any) -> set[str]:
    if not isinstance(data, dict):
        return set()

    names: set[str] = set()
    connectors = data.get("connectors", [])
    if isinstance(connectors, list):
        for connector in connectors:
            if isinstance(connector, dict):
                name = (
                    connector.get("cr_name")
                    or connector.get("crName")
                    or connector.get("name")
                    or connector.get("connector_name")
                    or connector.get("connectorName")
                )
                if isinstance(name, str) and name:
                    names.add(name)

    for key in ("sourceConnector", "source_connector", "sinkConnector", "sink_connector"):
        connector = data.get(key)
        if isinstance(connector, dict):
            name = connector.get("name") or connector.get("cr_name") or connector.get("crName")
            if isinstance(name, str) and name:
                names.add(name)
    return names


def _identifier_required_message(selected_tools: set[str]) -> str:
    if selected_tools & _CONSUMER_GROUP_PARAM_TOOLS:
        return (
            "Kafka Connect lag 조회에 사용할 커넥터 이름 또는 consumer group을 확정할 수 없습니다. "
            "프로젝트에 connector가 여러 개일 수 있으니 조회할 커넥터 이름을 알려주세요. "
            "consumer group을 직접 지정하려면 `connect-<connectorName>` 형식으로 입력해 주세요."
        )
    return (
        "커넥터 조회에 사용할 connector 이름을 확정할 수 없습니다. "
        "프로젝트에 connector가 여러 개일 수 있으니 상태나 trace를 확인할 커넥터 이름을 알려주세요."
    )
