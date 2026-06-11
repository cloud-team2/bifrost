"""Evidence API — state_patch evidence namespace lookup."""
from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, status
from fastapi.responses import JSONResponse

from app.persistence.evidence_repository import AnyEvidenceRepo, get_evidence_repo
from app.persistence.state_repository import PostgresStateRepository
from app.schemas import ApiResponse, ErrorCode

router = APIRouter()

_state_repo: PostgresStateRepository | None = None
_evidence_repo: AnyEvidenceRepo | None = None


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


def _failure(
    request_id: str,
    code: ErrorCode | str,
    message: str,
    *,
    status_code: int = status.HTTP_200_OK,
    retryable: bool = False,
) -> ApiResponse | JSONResponse:
    if isinstance(code, ErrorCode):
        return ApiResponse.failure(request_id, code, message, retryable=retryable)
    return JSONResponse(
        status_code=status_code,
        content={
            "ok": False,
            "request_id": request_id,
            "data": None,
            "error": {"code": code, "message": message, "retryable": retryable},
        },
    )


def get_state_repo() -> PostgresStateRepository:
    """Local provider kept here because persistence modules are not in this task's owned paths."""
    global _state_repo
    if _state_repo is None:
        _state_repo = PostgresStateRepository()
    return _state_repo


def get_raw_evidence_repo() -> AnyEvidenceRepo:
    """Provider kept patchable for route tests."""
    global _evidence_repo
    if _evidence_repo is None:
        _evidence_repo = get_evidence_repo()
    return _evidence_repo


def _value_as_str(value: Any) -> str | None:
    if value is None:
        return None
    value = getattr(value, "value", value)
    return value if isinstance(value, str) else str(value)


async def _get_patches(run_id: str) -> list[Any]:
    return await get_state_repo().get_patches(run_id)


def _patch_payload(patch: Any) -> dict[str, Any]:
    payload = getattr(patch, "patch", {})
    return payload if isinstance(payload, dict) else {}


def _evidence_summary(patch: Any) -> dict[str, Any]:
    payload = _patch_payload(patch)
    created_at = getattr(patch, "created_at", None)
    return {
        "evidence_id": _value_as_str(payload.get("evidence_id")),
        "type": _value_as_str(payload.get("type")),
        "store_ref": _value_as_str(payload.get("store_ref")),
        "summary": _value_as_str(payload.get("summary")),
        "redaction_status": _value_as_str(payload.get("redaction_status")),
        "collected_at": created_at.isoformat() if created_at else None,
    }


async def _hydrate_by_store_ref(store_ref: str | None) -> dict[str, Any] | None:
    if not store_ref:
        return None
    record = await get_raw_evidence_repo().get(store_ref)
    if record is None:
        return None
    return {
        "store_ref": record.store_ref,
        "payload": record.payload,
        "redaction_status": record.redaction_status,
        "status": record.status,
        "tool_name": record.tool_name,
        "step_id": record.step_id,
        "created_at": record.created_at.isoformat() if record.created_at else None,
    }


@router.get("/runs/{run_id}/evidence")
async def list_evidence(run_id: str) -> ApiResponse:
    patches = await _get_patches(run_id)
    items = [
        _evidence_summary(patch)
        for patch in patches
        if (
            getattr(patch, "namespace", None) == "evidence"
            and _patch_payload(patch).get("evidence_id")
        )
    ]
    return ApiResponse.success(_request_id(), {"items": items})


@router.get("/runs/{run_id}/evidence/{evidence_id}")
async def get_evidence(run_id: str, evidence_id: str) -> Any:
    request_id = _request_id()
    patches = await _get_patches(run_id)
    for patch in patches:
        payload = _patch_payload(patch)
        if (
            getattr(patch, "namespace", None) == "evidence"
            and _value_as_str(payload.get("evidence_id")) == evidence_id
        ):
            data = dict(payload)
            hydrated = await _hydrate_by_store_ref(_value_as_str(payload.get("store_ref")))
            if hydrated is not None:
                data["raw"] = hydrated
            return ApiResponse.success(request_id, data)
    return _failure(
        request_id,
        "EVIDENCE_NOT_FOUND",
        f"evidence not found: {evidence_id}",
        status_code=status.HTTP_404_NOT_FOUND,
    )


@router.post("/runs/{run_id}/evidence/{evidence_id}/hydrate")
async def hydrate_evidence(run_id: str, evidence_id: str) -> Any:
    request_id = _request_id()
    patches = await _get_patches(run_id)
    for patch in patches:
        payload = _patch_payload(patch)
        if (
            getattr(patch, "namespace", None) == "evidence"
            and _value_as_str(payload.get("evidence_id")) == evidence_id
        ):
            hydrated = await _hydrate_by_store_ref(_value_as_str(payload.get("store_ref")))
            if hydrated is None:
                return _failure(
                    request_id,
                    "EVIDENCE_RAW_NOT_FOUND",
                    f"raw evidence not found for: {evidence_id}",
                    status_code=status.HTTP_404_NOT_FOUND,
                )
            return ApiResponse.success(request_id, hydrated)
    return _failure(
        request_id,
        "EVIDENCE_NOT_FOUND",
        f"evidence not found: {evidence_id}",
        status_code=status.HTTP_404_NOT_FOUND,
    )
