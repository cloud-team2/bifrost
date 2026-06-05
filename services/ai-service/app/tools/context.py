"""Tool call context helpers.

The canonical schema lives in app.schemas.tools. This module keeps the runtime
tool package import surface small while avoiding duplicate Pydantic models.
"""
from __future__ import annotations

from app.schemas.tools import ToolContext

DEFAULT_ACTOR_ID = "bifrost-agent"


def spring_headers(context: ToolContext, actor_id: str = DEFAULT_ACTOR_ID) -> dict[str, str]:
    return context.spring_headers(actor_id=actor_id)
