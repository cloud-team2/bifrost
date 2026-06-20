"""KEDB CRUD API (#894)."""
from __future__ import annotations

import uuid
from datetime import date

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from app.catalogs.root_causes import get_root_cause
from app.persistence.kedb_repository import KedbRecordModel
from app.schemas import ApiResponse, ErrorCode

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class KedbRecordRequest(StrictModel):
    root_cause_id: str
    owner: str
    known_symptoms: list[str] = Field(default_factory=list)
    verified_fixes: list[str] = Field(default_factory=list)
    rollback_procedure: str | None = None
    recurrence_count: int = Field(default=0, ge=0)
    last_seen: date | None = None
    incident_links: list[str] = Field(default_factory=list)


@router.get("/catalogs/kedb")
async def list_kedb_records() -> ApiResponse:
    from app.persistence.kedb_repository import get_kedb_repo

    records = await get_kedb_repo().list()
    return ApiResponse.success(_request_id(), {"items": [_record_payload(record) for record in records]})


@router.get("/catalogs/kedb/{root_cause_id}", response_model=None)
async def get_kedb_record(root_cause_id: str) -> ApiResponse | JSONResponse:
    from app.persistence.kedb_repository import get_kedb_repo

    request_id = _request_id()
    record = await get_kedb_repo().get(root_cause_id)
    if record is None:
        body = ApiResponse.failure(request_id, ErrorCode.NOT_IMPLEMENTED, f"KEDB record not found: {root_cause_id}")
        return JSONResponse(status_code=404, content=body.model_dump(mode="json"))
    return ApiResponse.success(request_id, _record_payload(record))


@router.post("/catalogs/kedb", response_model=None)
async def create_kedb_record(req: KedbRecordRequest) -> ApiResponse | JSONResponse:
    return await _upsert(req)


@router.put("/catalogs/kedb/{root_cause_id}", response_model=None)
async def update_kedb_record(root_cause_id: str, req: KedbRecordRequest) -> ApiResponse | JSONResponse:
    if req.root_cause_id != root_cause_id:
        body = ApiResponse.failure(_request_id(), ErrorCode.VALIDATION_FAILED, "root_cause_id path/body mismatch")
        return JSONResponse(status_code=400, content=body.model_dump(mode="json"))
    return await _upsert(req)


@router.delete("/catalogs/kedb/{root_cause_id}", response_model=None)
async def delete_kedb_record(root_cause_id: str) -> ApiResponse | JSONResponse:
    from app.persistence.kedb_repository import get_kedb_repo

    request_id = _request_id()
    deleted = await get_kedb_repo().delete(root_cause_id)
    if not deleted:
        body = ApiResponse.failure(request_id, ErrorCode.NOT_IMPLEMENTED, f"KEDB record not found: {root_cause_id}")
        return JSONResponse(status_code=404, content=body.model_dump(mode="json"))
    return ApiResponse.success(request_id, {"deleted": True})


async def _upsert(req: KedbRecordRequest) -> ApiResponse | JSONResponse:
    from app.persistence.kedb_repository import get_kedb_repo

    request_id = _request_id()
    if get_root_cause(req.root_cause_id) is None:
        body = ApiResponse.failure(request_id, ErrorCode.VALIDATION_FAILED, f"unknown root_cause_id: {req.root_cause_id}")
        return JSONResponse(status_code=400, content=body.model_dump(mode="json"))
    record = await get_kedb_repo().upsert(KedbRecordModel(**req.model_dump()))
    return ApiResponse.success(request_id, _record_payload(record))


def _record_payload(record: KedbRecordModel) -> dict[str, object]:
    return record.model_dump(mode="json")
