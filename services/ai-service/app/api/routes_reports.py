"""Report API — final report snapshot lookup."""
from __future__ import annotations

import uuid

from fastapi import APIRouter

from app.persistence.report_repository import get_report_repo
from app.schemas import ApiResponse, ErrorCode

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


@router.get("/agent/runs/{run_id}/report")
async def get_run_report(run_id: str) -> ApiResponse:
    request_id = _request_id()
    snapshot = await get_report_repo().get_latest(run_id)
    if snapshot is None:
        return ApiResponse.failure(request_id, ErrorCode.RUN_NOT_FOUND, f"report not found for run: {run_id}")
    return ApiResponse.success(request_id, snapshot.model_dump(mode="json"))


@router.get("/incidents/{incident_id}/reports")
async def list_incident_reports(incident_id: str) -> ApiResponse:
    request_id = _request_id()
    repo = get_report_repo()
    snapshots = await repo.list_by_incident(incident_id)
    if not snapshots:
        # #932: 승인 대기(approval pause) 등으로 verified 리포트가 아직 없으면, unverified
        # 리포트(=pause 시점 RCA+권장조치)로 폴백해 인시던트 상세에 권장조치가 표시되도록 한다.
        snapshots = await repo.list_by_incident(incident_id, verified_only=False)
    return ApiResponse.success(
        request_id,
        {"incident_id": incident_id, "reports": [s.model_dump(mode="json") for s in snapshots]},
    )
