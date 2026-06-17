"""Planner agent — LLM 으로 read-only tool 선택, keyword fallback.

설계상 Planner 는 LLM agent 다. 사용자 의도와 catalog(§8 Read-only Runtime Tool
Catalog)를 보고 적합한 조회 tool 을 allowlist 안에서만 고른다(자유 생성 금지).
LLM 미가용·파싱 실패 시 keyword 매칭으로 fallback 해 회귀를 막는다(#483).
Tool names follow tool-catalog.md §8 Read-only Runtime Tool Catalog.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any

from app.prompts import planner as planner_prompt
from app.schemas.outputs import PlannerOutput, RetrievalPlanStep
from app.schemas.tools import ToolContext, ToolStatus
from app.tools.registry import ToolClientRegistry

_LOG_PARAMS = {"query": "pipeline events", "time_range": {"from": "now-30m", "to": "now"}}
_INCIDENT_LOG_PARAMS = {"query": "error exception failure", "time_range": {"from": "now-1h", "to": "now"}}
# list_project_pipelines (operation: list_project_pipelines) takes no params — ListProjectPipelinesParams is empty.
_PIPELINE_LIST_PARAMS: dict = {}
_PIPELINE_STATUS_PARAMS: dict = {}
_PROJECT_SCOPE_PARAMS: dict = {}
_EVENT_SUMMARY_PARAMS = {"window": "2h", "level": "warn+"}

_DEFAULT_TOOL = ("search_logs", _LOG_PARAMS)

# LLM 이 고를 수 있는 read-only tool catalog (§8). allowlist 밖 tool 선택 금지.
# 각 tool 의 기본 params 는 keyword 경로와 동일하게 맞춰 식별자 해석(#489)으로 위임한다.
_READ_TOOL_DEFAULT_PARAMS: dict[str, dict] = {
    "list_project_pipelines": _PIPELINE_LIST_PARAMS,
    "list_pipelines": _PIPELINE_STATUS_PARAMS,
    "get_pipeline_topology": {},
    "list_connectors": _PROJECT_SCOPE_PARAMS,
    "get_cluster_info": _PROJECT_SCOPE_PARAMS,
    "get_consumer_groups": _PROJECT_SCOPE_PARAMS,
    "analyze_event_log": _EVENT_SUMMARY_PARAMS,
    "search_logs": _LOG_PARAMS,
    "get_metrics": {"metric": "pipeline_lag_seconds", "time_range": "last_30m"},
    "get_deployments": {},
    "get_connector_status": {},
    "get_consumer_lag": {},
    "get_traces": {},
    "get_connector_task_trace": {},
    "get_alerts": {},
}
_READ_TOOL_DESCRIPTIONS: dict[str, str] = {
    "list_project_pipelines": "프로젝트 파이프라인 목록 조회",
    "list_pipelines": "프로젝트 파이프라인 상태·lag 요약 조회",
    "get_pipeline_topology": "특정 파이프라인의 source/topic/sink/connector 토폴로지 조회",
    "list_connectors": "프로젝트 Kafka Connect 커넥터 목록·상태 조회",
    "get_cluster_info": "Kafka broker/controller/topic partition 상태 조회",
    "get_consumer_groups": "프로젝트 Kafka consumer group 목록·lag 요약 조회",
    "analyze_event_log": "프로젝트 이벤트·인시던트 경고 요약 조회",
    "search_logs": "파이프라인/에러 로그 검색",
    "get_metrics": (
        "메트릭·지표·성능 수치 조회"
        " (pipeline_lag_seconds, consumer_lag_p95, consumer_commit_rate_per_sec,"
        " topic_ingress_messages_per_sec, source_freshness_delay_ms,"
        " source_watermark_delay_ms, source_event_rate_per_sec, broker_cpu_cores,"
        " broker_fs_read_bytes_per_sec 등)"
    ),
    "get_deployments": "최근 배포·변경 이력 조회",
    "get_connector_status": "특정 Kafka Connect 커넥터 상태 조회",
    "get_consumer_lag": "특정 Kafka consumer group lag(지연) 조회",
    "get_traces": "특정 커넥터 분산 trace(지연/실패 구간) 조회",
    "get_connector_task_trace": "특정 커넥터 task 예외 stack trace 조회",
    "get_alerts": "발생한 alert·알람 조회",
}
_READ_TOOL_ALLOWLIST = frozenset(_READ_TOOL_DEFAULT_PARAMS)
_TOOL_CATALOG = [
    {"tool_name": name, "description": _READ_TOOL_DESCRIPTIONS[name]}
    for name in _READ_TOOL_DEFAULT_PARAMS
]

_CONNECTOR_PARAM_TOOLS = frozenset({"get_connector_status", "get_traces", "get_connector_task_trace"})
_CONSUMER_GROUP_PARAM_TOOLS = frozenset({"get_consumer_lag", "get_kafka_lag"})
_PIPELINE_ID_PARAM_TOOLS = frozenset({"get_pipeline_topology"})
_OBSERVABILITY_TARGET_TOOLS = frozenset({"get_alerts", "analyze_event_log"})
# discovery tool — 파이프라인/커넥터 식별자를 산출한다. 식별자에 의존하는 조회는
# 이 step들이 끝난 뒤 실행돼야 정확하므로 retrieval에서 순차 chain으로 풀린다 (#481).
_DISCOVERY_TOOLS = frozenset({"list_project_pipelines", "get_pipeline_topology"})
# 특정 connector/consumer 식별자를 파라미터로 받는 조회 — discovery에 의존한다.
_IDENTIFIER_DEPENDENT_TOOLS = _CONNECTOR_PARAM_TOOLS | _CONSUMER_GROUP_PARAM_TOOLS
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
    "alert",
    "alerts",
    "event",
    "events",
    "log",
    "logs",
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
    selected = await _llm_select_tools(user_message)
    selected_by_llm = selected is not None
    if selected is None:
        selected = _keyword_select_tools(user_message)
        llm_selected_tools: set[str] = set()
    else:
        llm_selected_tools = {tool for tool, _ in selected}

    selected = _augment_hypothesis_routes(selected, user_message.lower())
    selected_tools = {tool for tool, _ in selected}
    if selected_tools & _PIPELINE_ID_PARAM_TOOLS:
        pipeline_id = _extract_pipeline_id(user_message)
        if pipeline_id is None:
            selected = _fallback_to_project_scope_tools(selected)
        else:
            selected = [
                (tool, _params_with_pipeline_id(tool, params, pipeline_id))
                for tool, params in selected
            ]
        selected_tools = {tool for tool, _ in selected}

    if selected_tools & _IDENTIFIER_DEPENDENT_TOOLS:
        identifier = _extract_identifier(user_message)
        if identifier is None and registry is not None and tool_context is not None:
            identifier = await _resolve_single_project_connector(registry, tool_context)

        if identifier is None:
            if selected_by_llm and (llm_selected_tools & _IDENTIFIER_DEPENDENT_TOOLS):
                return PlannerOutput(
                    retrieval_plan=[],
                    clarification_message=_identifier_required_message(selected_tools),
                )
            selected = _fallback_to_project_scope_tools(selected)
        else:
            connector_identifier = _extract_connector_identifier(user_message)
            consumer_group_identifier = _extract_consumer_group_identifier(user_message)
            if connector_identifier is None and identifier.kind == "connector":
                connector_identifier = identifier
            if consumer_group_identifier is None and identifier.kind == "consumer_group":
                consumer_group_identifier = identifier
            selected = [
                (
                    tool,
                    _params_with_identifier(
                        tool,
                        params,
                        _identifier_for_tool(tool, connector_identifier, consumer_group_identifier, identifier),
                    ),
                )
                for tool, params in selected
            ]

    if selected_tools & _OBSERVABILITY_TARGET_TOOLS:
        identifier = _extract_identifier(user_message)
        if identifier is not None:
            selected = [
                (
                    tool,
                    _params_with_identifier(tool, params, identifier)
                    if tool in _OBSERVABILITY_TARGET_TOOLS
                    else params,
                )
                for tool, params in selected
            ]

    # depends_on 채우기 (#481): 식별자 의존 조회는 같은 plan의 discovery step에
    # 의존시켜 retrieval이 순차 chain으로 풀게 한다. 그 외 독립 tool은 depends_on을
    # 비워 병렬(fan-out) 실행을 유지한다.
    step_ids = [f"step_{i + 1:03d}" for i in range(len(selected))]
    discovery_step_ids = [
        sid for sid, (tool, _) in zip(step_ids, selected) if tool in _DISCOVERY_TOOLS
    ]
    steps = [
        RetrievalPlanStep(
            step_id=step_ids[i],
            tool_name=tool,
            params=params,
            purpose=f"{tool} 조회",
            required=True,
            depends_on=(
                [sid for sid in discovery_step_ids if sid != step_ids[i]]
                if tool in _IDENTIFIER_DEPENDENT_TOOLS
                else []
            ),
            plan_hash=_plan_hash(tool, params),
        )
        for i, (tool, params) in enumerate(selected)
    ]

    return PlannerOutput(retrieval_plan=steps)


def _keyword_select_tools(user_message: str) -> list[tuple[str, dict]]:
    """LLM 미가용 시 fallback — 기존 keyword→tool 매칭(회귀 보존)."""
    msg = user_message.lower()
    selected: list[tuple[str, dict]] = []
    seen_tools: set[tuple[str, str]] = set()

    def add(tool: str, params: dict) -> None:
        dedupe_key = (
            tool,
            json.dumps(params, sort_keys=True) if tool == "get_metrics" else "",
        )
        if dedupe_key not in seen_tools:
            selected.append((tool, params))
            seen_tools.add(dedupe_key)

    has_stack = _has_any(msg, {"스택", "스택트레이스", "stacktrace", "예외", "exception"})
    has_trace = _has_any(msg, {"trace", "span", "latency", "분산추적", "지연추적"})
    has_event_summary = _has_any(msg, {"이벤트", "event", "인시던트", "incident", "장애"})
    has_pipeline = _has_any(msg, {"파이프라인", "pipeline"})
    has_pipeline_list = _has_any(msg, {"리스트", "목록", "연결"})
    has_lag = _has_any(msg, {"lag", "지연"})
    has_connector = _has_any(msg, {"커넥터", "connector"})
    identifier = _extract_identifier(msg)

    if has_stack:
        if identifier is not None:
            add("get_connector_task_trace", {})
        elif has_connector:
            add("list_connectors", _PROJECT_SCOPE_PARAMS)
    elif has_trace:
        if identifier is not None:
            add("get_traces", {})
        elif has_connector:
            add("list_connectors", _PROJECT_SCOPE_PARAMS)

    if has_event_summary:
        add("analyze_event_log", _EVENT_SUMMARY_PARAMS)
    if _has_any(msg, {"alert", "알람", "알럿", "경보"}):
        add("get_alerts", {})
    if has_connector and not (has_stack or has_trace):
        if identifier is not None:
            add("get_connector_status", {})
        else:
            add("list_connectors", _PROJECT_SCOPE_PARAMS)
    if has_pipeline:
        if _has_any(msg, {"토폴로지", "topology"}):
            add("get_pipeline_topology", {})
        elif has_lag or (_has_any(msg, {"상태", "현황", "status"}) and not has_pipeline_list):
            add("list_pipelines", _PIPELINE_STATUS_PARAMS)
        else:
            add("list_project_pipelines", _PIPELINE_LIST_PARAMS)
    if _has_any(msg, {"consumer group", "consumer-group", "컨슈머", "consumer"}) or (has_lag and not has_pipeline):
        if has_lag:
            add("get_consumer_lag", {})
        else:
            add("get_consumer_groups", _PROJECT_SCOPE_PARAMS)
    if _has_any(msg, {"로그", "log"}) and not has_event_summary:
        add("search_logs", _LOG_PARAMS)
    if _has_any(msg, {"오류", "에러"}) and not has_event_summary and ("search_logs", "") not in seen_tools:
        add("search_logs", _INCIDENT_LOG_PARAMS)
    if _has_any(msg, {"메트릭", "metric", "지표", "수치", "성능"}):
        for params in _metric_param_list_for_message(msg):
            add("get_metrics", params)
    if _has_any(msg, {"배포", "deploy", "변경", "change", "토폴로지", "topology"}):
        add("get_deployments", {})

    if not selected:
        selected.append(_DEFAULT_TOOL)
    return selected


def _augment_hypothesis_routes(selected: list[tuple[str, dict]], msg: str) -> list[tuple[str, dict]]:
    """Add deterministic read tools/metrics for incident hypotheses.

    LLM selection remains allowlisted, but RCA prompts often describe a broad
    active incident and ask for metrics without naming each metric. This expands
    those broad hypotheses into concrete Spring-backed metric names.
    """
    augmented = list(selected)
    seen: set[tuple[str, str]] = {
        (
            tool,
            json.dumps(params, sort_keys=True) if tool == "get_metrics" else "",
        )
        for tool, params in augmented
    }

    def add(tool: str, params: dict) -> None:
        dedupe_key = (
            tool,
            json.dumps(params, sort_keys=True) if tool == "get_metrics" else "",
        )
        if dedupe_key not in seen:
            augmented.append((tool, params))
            seen.add(dedupe_key)

    incidentish = _has_any(
        msg,
        {
            "incident_analysis",
            "active incident",
            "root cause",
            "root_cause",
            "근본 원인",
            "장애",
            "인시던트",
        },
    )
    metrics_requested = _has_any(msg, {"metric", "metrics", "메트릭", "지표", "수치"})
    broad_metric_probe = incidentish and metrics_requested

    has_lag = _has_any(msg, {"consumer lag", "consumer_group", "lag", "지연", "offset progression"})
    has_freshness = _has_any(msg, {"freshness", "watermark", "신선도", "워터마크"})
    has_ingress = _has_any(msg, {"ingress", "incoming", "messages", "topic", "유입", "토픽"})
    has_broker = _has_any(msg, {"broker", "브로커", "cluster", "클러스터"})
    has_auth = _has_any(
        msg,
        {"auth", "authentication", "permission", "credential", "token", "인증", "권한", "비밀번호"},
    )
    has_schema = _has_any(
        msg,
        {"schema", "serialization", "deserialization", "config", "스키마", "역직렬화", "설정"},
    )

    if "pipeline_id=" in msg:
        add("get_pipeline_topology", {})
    if (has_lag and not _has_any(msg, {"pipeline", "파이프라인"})) or broad_metric_probe:
        add("get_consumer_lag", {})
        add("get_metrics", {"metric": "consumer_lag_p95", "time_range": "last_30m"})
        add("get_metrics", {"metric": "consumer_commit_rate_per_sec", "time_range": "last_30m"})
    if has_freshness or broad_metric_probe:
        add("get_metrics", {"metric": "source_freshness_delay_ms", "time_range": "last_30m"})
        add("get_metrics", {"metric": "source_watermark_delay_ms", "time_range": "last_30m"})
    if has_ingress or broad_metric_probe:
        add("get_metrics", {"metric": "topic_ingress_messages_per_sec", "time_range": "last_30m"})
        add("get_metrics", {"metric": "source_event_rate_per_sec", "time_range": "last_30m"})
    if has_broker or broad_metric_probe:
        add("get_cluster_info", _PROJECT_SCOPE_PARAMS)
        add("get_metrics", {"metric": "broker_cpu_cores", "time_range": "last_30m"})
        add("get_metrics", {"metric": "broker_fs_read_bytes_per_sec", "time_range": "last_30m"})
    if has_auth or has_schema or incidentish:
        add("analyze_event_log", _EVENT_SUMMARY_PARAMS)
    if has_auth or has_schema:
        add("get_connector_task_trace", {})
        add("search_logs", _INCIDENT_LOG_PARAMS)
    if has_schema:
        add("get_deployments", {})

    return augmented


async def _llm_select_tools(user_message: str) -> list[tuple[str, dict]] | None:
    """LLM 으로 catalog allowlist 안의 read tool 을 고른다. 실패 시 None(→ keyword)."""
    from app.llm.structured import complete_structured

    messages = [
        {"role": "system", "content": planner_prompt.SYSTEM_PROMPT},
        {"role": "user", "content": planner_prompt.build_user_prompt(user_message, _TOOL_CATALOG)},
    ]
    tools = await complete_structured(
        "planner",
        messages,
        _validate_tool_selection,
        repair_hint=planner_prompt.REPAIR_HINT,
    )
    if not tools:
        return None
    # catalog 기본 params 로 step 구성 — 식별자 해석은 downstream(#489) 에 위임.
    selected: list[tuple[str, dict]] = []
    for tool in tools:
        if tool == "get_metrics":
            selected.extend(("get_metrics", params) for params in _metric_param_list_for_message(user_message.lower()))
        else:
            selected.append((tool, dict(_READ_TOOL_DEFAULT_PARAMS[tool])))
    return selected


def _metric_params_for_message(msg: str) -> dict:
    """Map metric intent to the first live-backed logical metric name supported by Spring."""
    return _metric_param_list_for_message(msg)[0]


def _metric_param_list_for_message(msg: str) -> list[dict]:
    """Map metric intent to live-backed logical metric names supported by Spring."""
    metrics: list[str] = []

    def add_metric(metric: str) -> None:
        if metric not in metrics:
            metrics.append(metric)

    if _has_any(msg, {"p95", "lag p95", "lag 급증", "지연 p95"}):
        add_metric("consumer_lag_p95")
    if _has_any(msg, {"commit rate", "offset progression", "offset 진행", "오프셋", "커밋"}):
        add_metric("consumer_commit_rate_per_sec")
    if _has_any(msg, {"ingress", "incoming", "messages in", "유입", "토픽 유입", "topic ingress"}):
        add_metric("topic_ingress_messages_per_sec")
    if _has_any(msg, {"watermark", "워터마크"}):
        add_metric("source_watermark_delay_ms")
    if _has_any(msg, {"freshness", "behind source", "신선도"}):
        add_metric("source_freshness_delay_ms")
    if _has_any(msg, {"source event", "source volume", "event rate", "소스 이벤트", "소스 볼륨"}):
        add_metric("source_event_rate_per_sec")
    if _has_any(msg, {"broker", "브로커"}):
        if _has_any(msg, {"memory", "mem", "메모리"}):
            add_metric("broker_memory_working_set_bytes")
        if _has_any(msg, {"transmit", "tx", "송신"}):
            add_metric("broker_network_transmit_bytes_per_sec")
        if _has_any(msg, {"network", "receive", "rx", "네트워크", "수신"}):
            add_metric("broker_network_receive_bytes_per_sec")
        if _has_any(msg, {"read", "reads", "읽기"}):
            add_metric("broker_fs_read_bytes_per_sec")
        if _has_any(msg, {"disk", "fs", "write", "writes", "디스크", "쓰기"}):
            add_metric("broker_fs_write_bytes_per_sec")
        if not any(metric.startswith("broker_") for metric in metrics):
            add_metric("broker_cpu_cores")

    if not metrics:
        add_metric("pipeline_lag_seconds")
    return [{"metric": metric, "time_range": "last_30m"} for metric in metrics]


def _validate_tool_selection(parsed: dict[str, Any]) -> list[str] | None:
    raw_tools = parsed.get("tools")
    if not isinstance(raw_tools, list):
        return None

    selected: list[str] = []
    seen: set[str] = set()
    for item in raw_tools:
        if not isinstance(item, str):
            continue
        name = item.strip()
        # allowlist 밖 tool(자유 생성·조치 tool)은 버린다 — 한 tool 은 한 번만.
        if name in _READ_TOOL_ALLOWLIST and name not in seen:
            selected.append(name)
            seen.add(name)

    return selected or None


def _fallback_to_project_scope_tools(selected: list[tuple[str, dict]]) -> list[tuple[str, dict]]:
    fallback: list[tuple[str, dict]] = []
    seen: set[tuple[str, str]] = set()
    for tool, params in selected:
        if tool in _CONNECTOR_PARAM_TOOLS:
            next_tool, next_params = "list_connectors", _PROJECT_SCOPE_PARAMS
        elif tool in _CONSUMER_GROUP_PARAM_TOOLS:
            next_tool, next_params = "get_consumer_groups", _PROJECT_SCOPE_PARAMS
        elif tool in _PIPELINE_ID_PARAM_TOOLS:
            next_tool, next_params = "list_project_pipelines", _PIPELINE_LIST_PARAMS
        else:
            next_tool, next_params = tool, params
        dedupe_key = (
            next_tool,
            json.dumps(next_params, sort_keys=True) if next_tool == "get_metrics" else "",
        )
        if dedupe_key not in seen:
            fallback.append((next_tool, next_params))
            seen.add(dedupe_key)
    return fallback


def _params_with_pipeline_id(tool: str, params: dict, pipeline_id: str) -> dict:
    next_params = dict(params)
    if tool in _PIPELINE_ID_PARAM_TOOLS:
        next_params["pipeline_id"] = pipeline_id
    return next_params


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
    elif tool in _OBSERVABILITY_TARGET_TOOLS:
        connector_name = identifier.value
        if identifier.kind == "consumer_group" and connector_name.lower().startswith("connect-"):
            connector_name = connector_name[len("connect-"):]
        next_params["connector_name"] = connector_name
    return next_params


def _identifier_for_tool(
    tool: str,
    connector_identifier: _Identifier | None,
    consumer_group_identifier: _Identifier | None,
    fallback: _Identifier,
) -> _Identifier:
    if tool in _CONNECTOR_PARAM_TOOLS:
        return connector_identifier or consumer_group_identifier or fallback
    if tool in _CONSUMER_GROUP_PARAM_TOOLS:
        return consumer_group_identifier or connector_identifier or fallback
    if tool in _OBSERVABILITY_TARGET_TOOLS:
        return connector_identifier or fallback
    return fallback


def _extract_identifier(user_message: str) -> _Identifier | None:
    explicit_connector = _extract_connector_identifier(user_message)
    if explicit_connector is not None:
        return explicit_connector

    explicit_group = _extract_consumer_group_identifier(user_message)
    if explicit_group is not None:
        return explicit_group

    quoted = re.search(r"`([^`]+)`|['\"]([^'\"]+)['\"]", user_message)
    if quoted:
        value = _clean_identifier(quoted.group(1) or quoted.group(2))
        if _is_identifier(value):
            return _Identifier(value=value, kind=_kind_for(value))

    group_match = re.search(rf"\b(connect-{_IDENTIFIER_RE})\b", user_message, flags=re.IGNORECASE)
    if group_match:
        return _Identifier(value=_clean_identifier(group_match.group(1)), kind="consumer_group")

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
        value = _clean_identifier(match.group(1))
        if _is_identifier(value):
            return _Identifier(value=value, kind=_kind_for(value))

    return None


def _extract_connector_identifier(user_message: str) -> _Identifier | None:
    explicit_connector = re.search(rf"\bconnector_name\s*=\s*({_IDENTIFIER_RE})", user_message, flags=re.IGNORECASE)
    if explicit_connector:
        value = _clean_identifier(explicit_connector.group(1))
        if _is_identifier(value):
            return _Identifier(value=value, kind="connector")
    return None


def _extract_consumer_group_identifier(user_message: str) -> _Identifier | None:
    explicit_group = re.search(rf"\bconsumer_group\s*=\s*({_IDENTIFIER_RE})", user_message, flags=re.IGNORECASE)
    if explicit_group:
        value = _clean_identifier(explicit_group.group(1))
        if _is_identifier(value):
            return _Identifier(value=value, kind="consumer_group")
    return None


def _extract_pipeline_id(user_message: str) -> str | None:
    explicit = re.search(rf"\bpipeline_id\s*=\s*({_IDENTIFIER_RE})", user_message, flags=re.IGNORECASE)
    if explicit:
        return _clean_identifier(explicit.group(1))
    labelled = re.search(
        rf"(?:pipeline|파이프라인)[\s_-]*(?:id|아이디)\s*[:=]\s*({_IDENTIFIER_RE})",
        user_message,
        flags=re.IGNORECASE,
    )
    if labelled:
        value = _clean_identifier(labelled.group(1))
        if _is_identifier(value):
            return value
    return None


def _clean_identifier(value: str) -> str:
    return value.strip().rstrip(".,;")


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


def _has_any(message: str, keywords: set[str]) -> bool:
    return any(keyword in message for keyword in keywords)
