"""Planner agent — keyword-based tool selection (no LLM).

Tool names follow tool-catalog.md §8 Read-only Runtime Tool Catalog.
"""
from __future__ import annotations

import hashlib
import json

from app.schemas.outputs import PlannerOutput, RetrievalPlanStep

_LOG_PARAMS = {"query": "pipeline events", "time_range": {"from": "now-30m", "to": "now"}}
_INCIDENT_LOG_PARAMS = {"query": "error exception failure", "time_range": {"from": "now-1h", "to": "now"}}

_KEYWORD_TOOL_MAP: list[tuple[set[str], str, dict]] = [
    # catalog §8.1 Observability — get_pipeline_logs (operation: search_logs)
    ({"파이프라인", "pipeline", "현황", "상태", "로그", "log"}, "get_pipeline_logs", _LOG_PARAMS),
    # catalog §8.1 Observability — get_metrics (operation: query_metrics)
    ({"메트릭", "metric", "지표", "수치", "성능"}, "get_metrics", {"metric": "pipeline_lag_seconds", "time_range": "last_30m"}),
    # catalog §8.2 Pipeline / Change — get_deployments (operation: get_recent_changes)
    ({"배포", "deploy", "변경", "change", "토폴로지", "topology"}, "get_deployments", {}),
    # catalog §8.3 Kafka Connect — get_connector_status
    ({"커넥터", "connector"}, "get_connector_status", {"connector_name": "default-connector"}),
    # catalog §8.3 Kafka Consumer — get_consumer_lag
    ({"lag", "컨슈머", "consumer", "지연"}, "get_consumer_lag", {"consumer_group": "default-group"}),
    # incident/장애 → log search for error evidence
    ({"인시던트", "incident", "장애", "오류", "에러"}, "get_pipeline_logs", _INCIDENT_LOG_PARAMS),
]
_DEFAULT_TOOL = ("get_pipeline_logs", _LOG_PARAMS)


def _plan_hash(tool_name: str, params: dict) -> str:
    canonical = json.dumps({"tool_name": tool_name, "params": params}, sort_keys=True)
    return hashlib.sha256(canonical.encode()).hexdigest()[:16]


async def run_planner(user_message: str, project_id: str) -> PlannerOutput:
    msg = user_message.lower()
    selected: list[tuple[str, dict]] = []

    for keywords, tool, params in _KEYWORD_TOOL_MAP:
        if any(kw in msg for kw in keywords):
            selected.append((tool, params))

    if not selected:
        selected.append(_DEFAULT_TOOL)

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
