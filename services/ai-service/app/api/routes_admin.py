"""Admin API (design fastapi/api.md §17)."""
from __future__ import annotations

import uuid
from enum import Enum

from fastapi import APIRouter, Depends, HTTPException, Request

from app.schemas import ApiResponse, ErrorCode


async def require_admin(request: Request) -> None:
    actor_type = request.headers.get("X-Actor-Type", "")
    if actor_type != "admin":
        raise HTTPException(status_code=403, detail="FORBIDDEN")


router = APIRouter(dependencies=[Depends(require_admin)])


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


@router.get("/models")
async def get_models() -> ApiResponse:
    from app.core.config import settings
    from app.llm.provider import get_llm_provider

    agent_tier = _load_agent_tier()
    provider_ok = False
    try:
        provider_ok = await get_llm_provider().health()
    except Exception:
        pass

    return ApiResponse.success(
        _request_id(),
        {
            "default_model": settings.llm_default_model,
            "agent_tier_mapping": {
                agent: _enum_value(tier)
                for agent, tier in agent_tier.items()
            },
            "agent_model_resolved": {
                agent: _model_for_agent(agent, settings.llm_default_model)
                for agent in agent_tier
            },
            "provider_status": "ok" if provider_ok else "unavailable",
        },
    )


@router.get("/dependencies")
async def get_dependencies() -> ApiResponse:
    from app.core.config import settings
    from app.core.db import _pool
    from app.tools.registry import get_tool_registry

    db_status = "unavailable"
    if _pool is not None:
        try:
            async with _pool.acquire() as conn:
                await conn.execute("SELECT 1")
            db_status = "ok"
        except Exception:
            pass

    try:
        spring_ok = await get_tool_registry().health()
        spring_status = "ok" if spring_ok else "unavailable"
    except Exception:
        spring_status = "unavailable"

    return ApiResponse.success(
        _request_id(),
        {
            "spring_operations": {
                "status": spring_status,
                "url": settings.spring_ops_base_url,
            },
            "agent_run_store": {
                "status": db_status,
                "url": "agentdb",
            },
        },
    )


@router.post("/runs/{run_id}/replay")
async def replay_run(run_id: str) -> ApiResponse:
    return ApiResponse.failure(
        _request_id(),
        ErrorCode.NOT_IMPLEMENTED,
        "run replay is not implemented in v1",
    )


@router.post("/catalogs/reload")
async def reload_catalogs() -> ApiResponse:
    from app.core.config import settings

    return ApiResponse.success(
        _request_id(),
        {
            "reloaded": False,
            "reason": "v1 catalogs are module-level constants — restart pod to apply changes",
            "catalog_version": settings.catalog_version,
        },
    )


def _load_agent_tier() -> dict[str, object]:
    try:
        from app.llm.model_router import AGENT_TIER

        return dict(AGENT_TIER)
    except Exception:
        return {}


def _model_for_agent(agent: str, default_model: str) -> str:
    try:
        from app.llm.model_router import model_for_agent

        return model_for_agent(agent)
    except Exception:
        return default_model


def _enum_value(value: object) -> object:
    if isinstance(value, Enum):
        return value.value
    return value.value if hasattr(value, "value") else str(value)
