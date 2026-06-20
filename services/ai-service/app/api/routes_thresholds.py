"""#884 threshold governance API — 임계값 조회·변경·롤백·이력."""
from __future__ import annotations

import uuid

from fastapi import APIRouter

from app.core.threshold_registry import (
    ThresholdUpdateRequest,
    get_threshold_registry,
)
from app.schemas import ApiResponse, ErrorCode

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


@router.get("/thresholds")
async def list_thresholds(category: str | None = None) -> ApiResponse:
    request_id = _request_id()
    registry = get_threshold_registry()
    entries = registry.list(category=category)
    return ApiResponse.success(request_id, {
        "thresholds": [e.model_dump(mode="json") for e in entries],
        "total": len(entries),
    })


@router.get("/thresholds/{threshold_name}")
async def get_threshold(threshold_name: str) -> ApiResponse:
    request_id = _request_id()
    entry = get_threshold_registry().get(threshold_name)
    if entry is None:
        return ApiResponse.failure(
            request_id, ErrorCode.VALIDATION_FAILED,
            f"threshold not found: {threshold_name}",
        )
    return ApiResponse.success(request_id, entry.model_dump(mode="json"))


@router.put("/thresholds/{threshold_name}")
async def update_threshold(
    threshold_name: str, req: ThresholdUpdateRequest
) -> ApiResponse:
    request_id = _request_id()
    updated = get_threshold_registry().update(
        threshold_name,
        value=req.value,
        basis=req.basis,
        owner=req.owner,
        dataset_version=req.dataset_version,
    )
    if updated is None:
        return ApiResponse.failure(
            request_id, ErrorCode.VALIDATION_FAILED,
            f"threshold not found: {threshold_name}",
        )
    return ApiResponse.success(request_id, updated.model_dump(mode="json"))


@router.post("/thresholds/{threshold_name}/rollback")
async def rollback_threshold(threshold_name: str) -> ApiResponse:
    request_id = _request_id()
    rolled_back = get_threshold_registry().rollback(threshold_name)
    if rolled_back is None:
        return ApiResponse.failure(
            request_id, ErrorCode.VALIDATION_FAILED,
            f"cannot rollback: {threshold_name} (no rollback value)",
        )
    return ApiResponse.success(request_id, rolled_back.model_dump(mode="json"))


@router.get("/thresholds/{threshold_name}/history")
async def threshold_history(threshold_name: str) -> ApiResponse:
    request_id = _request_id()
    history = get_threshold_registry().history(threshold_name)
    if not history:
        return ApiResponse.failure(
            request_id, ErrorCode.VALIDATION_FAILED,
            f"threshold not found: {threshold_name}",
        )
    return ApiResponse.success(request_id, {
        "threshold_name": threshold_name,
        "history": [e.model_dump(mode="json") for e in history],
    })
