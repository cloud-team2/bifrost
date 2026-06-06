"""Planner agent — keyword-based tool selection (no LLM)."""
from __future__ import annotations

import hashlib
import json

from app.schemas.outputs import PlannerOutput, RetrievalPlanStep

_KEYWORD_TOOL_MAP: list[tuple[set[str], str, dict]] = [
    ({"파이프라인", "pipeline"}, "list_project_pipelines", {}),
    ({"커넥터", "connector"}, "get_connector_status", {"connector_name": "default-connector"}),
    ({"lag", "컨슈머", "consumer"}, "get_consumer_lag", {"consumer_group": "default-group"}),
    ({"인시던트", "incident"}, "get_incident_summary", {"incident_id": "latest"}),
    ({"토폴로지", "topology"}, "get_pipeline_topology", {"pipeline_id": "default"}),
]
_DEFAULT_TOOL = ("list_project_pipelines", {})


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
