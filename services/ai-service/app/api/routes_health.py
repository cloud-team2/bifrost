"""Health / Metadata API (design fastapi/api.md §5)."""
from __future__ import annotations

import asyncio
import uuid

from fastapi import APIRouter

from app.core.config import settings
from app.schemas import ApiResponse
from app.tools.registry import get_tool_registry

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


async def _ping_with_timeout(coro, timeout: float = 2.0) -> str:
    try:
        result = await asyncio.wait_for(coro, timeout=timeout)
        return "ok" if result else "unavailable"
    except (asyncio.TimeoutError, Exception):
        return "unavailable"


@router.get("/health")
def health() -> ApiResponse:
    """FastAPI 서버 생존 상태."""
    return ApiResponse.success(_request_id(), {"status": "ok"})


@router.get("/ready")
async def ready() -> ApiResponse:
    """의존성(LLM, Spring Boot, State/Evidence Store) 준비 상태."""
    from app.core.db import _pool
    from app.knowledge.vector_store import get_vector_store
    from app.llm.provider import get_llm_provider

    db_status = "unknown"
    if _pool is not None:
        try:
            async with _pool.acquire() as conn:
                await asyncio.wait_for(conn.execute("SELECT 1"), timeout=2.0)
            db_status = "ok"
        except Exception:
            db_status = "unavailable"

    try:
        spring_ok = await asyncio.wait_for(get_tool_registry().health(), timeout=2.0)
        spring_status = "ok" if spring_ok else "unavailable"
    except Exception:
        spring_status = "unavailable"

    llm_status = await _ping_with_timeout(get_llm_provider().health(), timeout=5.0)
    vector_status = await _ping_with_timeout(get_vector_store().health(), timeout=2.0)
    evidence_status = spring_status

    return ApiResponse.success(
        _request_id(),
        {
            "status": "ready",
            "dependencies": {
                "spring_operations": spring_status,
                "llm_provider": llm_status,
                "agent_run_store": db_status,
                "vector_store": vector_status,
                "evidence_store": evidence_status,
            },
        },
    )


@router.get("/version")
def version() -> ApiResponse:
    return ApiResponse.success(
        _request_id(),
        {
            "api_version": "v1",
            "build_version": settings.version,
            "catalog_version": settings.catalog_version,
        },
    )


@router.get("/capabilities")
def capabilities() -> ApiResponse:
    return ApiResponse.success(
        _request_id(),
        {
            "modes": ["simple_query", "incident_analysis", "action_execution", "approval_decision"],
            "streaming": ["sse"],
            "model_tiers": [settings.llm_default_model],
        },
    )
