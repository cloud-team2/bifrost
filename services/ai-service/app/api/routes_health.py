"""Health / Metadata API (design fastapi/api.md §5)."""
from __future__ import annotations

import uuid

from fastapi import APIRouter

from app.core.config import settings
from app.schemas import ApiResponse

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


@router.get("/health")
def health() -> ApiResponse:
    """FastAPI 서버 생존 상태."""
    return ApiResponse.success(_request_id(), {"status": "ok"})


@router.get("/ready")
async def ready() -> ApiResponse:
    """의존성(LLM, Spring Boot, State/Evidence Store) 준비 상태."""
    from app.core.db import _pool

    db_status = "unknown"
    if _pool is not None:
        try:
            async with _pool.acquire() as conn:
                await conn.execute("SELECT 1")
            db_status = "ok"
        except Exception:
            db_status = "unavailable"

    return ApiResponse.success(
        _request_id(),
        {
            "status": "ready",
            "dependencies": {
                "spring_operations": "unknown",
                "llm_provider": "unknown",
                "agent_run_store": db_status,
                "vector_store": "unknown",
                "evidence_store": "unknown",
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
