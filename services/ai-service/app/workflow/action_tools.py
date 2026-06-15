"""Shared action-execution tool predicates."""
from __future__ import annotations

from typing import Any

from app.schemas.state import ActionType

ACTION_EXECUTION_TOOLS = frozenset({
    "pause_connector",
    "restart_connector",
    "restart_consumer_group",
    "resume_connector",
})


def nonempty_tool_name(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def is_action_execution_tool(value: Any) -> bool:
    tool_name = nonempty_tool_name(value)
    return tool_name in ACTION_EXECUTION_TOOLS


def is_executable_runtime_action_payload(value: dict[str, Any]) -> bool:
    action_type = getattr(value.get("action_type"), "value", value.get("action_type"))
    return (
        action_type == ActionType.RUNTIME_TOOL.value
        and is_action_execution_tool(value.get("tool_name"))
    )
