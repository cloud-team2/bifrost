"""Agent Run API (design fastapi/api.md §6~§7) — 스캐폴드.

run 생성/조회/SSE 구독 엔드포인트의 골격만 둔다. 실제 Supervisor/Agent 워크플로는 후속 이슈.
"""
from __future__ import annotations

import uuid

from fastapi import APIRouter
from pydantic import BaseModel

from app.schemas import ApiResponse, ErrorCode

router = APIRouter()


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


class CreateRunRequest(BaseModel):
    project_id: str
    mode: str | None = None
    message: str | None = None
    incident_id: str | None = None
    remediation_requested: bool = False
    stream: bool = True


@router.post("/runs")
def create_run(req: CreateRunRequest) -> ApiResponse:
    """새 Agent run 생성 (스캐폴드 — 아직 워크플로 미구현)."""
    return ApiResponse.failure(
        _request_id(),
        ErrorCode.NOT_IMPLEMENTED,
        "Agent run 워크플로는 아직 구현되지 않았습니다 (스캐폴드).",
    )


@router.get("/runs/{run_id}")
def get_run(run_id: str) -> ApiResponse:
    return ApiResponse.failure(
        _request_id(),
        ErrorCode.NOT_IMPLEMENTED,
        f"run 조회 미구현: {run_id}",
    )
