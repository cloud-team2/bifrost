"""Approval Gate API — 승인·거절 엔드포인트."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from app.persistence.approval_link_repository import ApprovalLink, get_approval_repo
from app.schemas import ApiResponse, ErrorCode
from app.schemas.api import ApprovalSummary, ApprovalsListResponse

router = APIRouter()
decision_router = APIRouter()


def _to_summary(link: ApprovalLink) -> ApprovalSummary:
    return ApprovalSummary(
        approval_id=link.approval_id,
        run_id=link.run_id,
        action_id=link.action_id,
        params_hash=link.params_hash,
        status=link.status,
        approved_by=link.approved_by,
        created_at=link.created_at,
        resolved_at=link.resolved_at,
    )


class ApproveRequest(BaseModel):
    approved_by: str = "operator"


class ApprovalDecisionRequest(BaseModel):
    decision: str
    comment: str | None = None


@decision_router.post("/approvals/{approval_id}/decision")
def decide_approval(approval_id: str, req: ApprovalDecisionRequest) -> ApiResponse:
    repo = get_approval_repo()
    if req.decision == "approved":
        link = repo.approve(approval_id)
    elif req.decision == "rejected":
        link = repo.reject(approval_id)
    else:
        raise HTTPException(status_code=400, detail=f"unknown decision: {req.decision}")

    if link is None:
        raise HTTPException(status_code=404, detail=f"approval not found: {approval_id}")
    return ApiResponse.success("", {"approval_id": approval_id, "status": link.status})


@router.post("/approvals/{approval_id}/approve")
def approve(approval_id: str, req: ApproveRequest) -> ApiResponse:
    repo = get_approval_repo()
    link = repo.approve(approval_id, approved_by=req.approved_by)
    if link is None:
        raise HTTPException(status_code=404, detail=f"approval not found: {approval_id}")
    return ApiResponse.success("", {"approval_id": approval_id, "status": link.status})


@router.post("/approvals/{approval_id}/reject")
def reject(approval_id: str) -> ApiResponse:
    repo = get_approval_repo()
    link = repo.reject(approval_id)
    if link is None:
        raise HTTPException(status_code=404, detail=f"approval not found: {approval_id}")
    return ApiResponse.success("", {"approval_id": approval_id, "status": link.status})


@router.get("/runs/{run_id}/approvals")
def list_pending(run_id: str) -> ApiResponse:
    repo = get_approval_repo()
    pending = repo.list_pending(run_id)
    return ApiResponse.success("", {
        "run_id": run_id,
        "pending": [
            {"approval_id": l.approval_id, "action_id": l.action_id, "params_hash": l.params_hash}
            for l in pending
        ],
    })


# ── issue #394: 글로벌 approval list + 단일 상세 (docs/api/fastapi.md §10) ──

@decision_router.get("/approvals")
async def list_approvals(
    status: str | None = Query(default=None, description="pending|approved|rejected"),
    project_id: str | None = Query(default=None, description="run.project_id 기준 필터"),
) -> ApiResponse:
    """글로벌 approval 목록.

    ai-service 의 approval_link repository 를 직접 조회한다 (Spring 의
    /internal/ops/approvals 는 multi-tenant tenantId 가 required 라 ai-service
    의 ToolContext.project_id 로 직접 매핑할 수 없음 — 별도 매핑 작업이
    선행되어야 함). 따라서 본 endpoint 는 ai-service 가 보유한 link 만 노출하며,
    Spring approval record 의 SoT 정본은 `/internal/ops/approvals` 직접 호출로
    조회한다.
    """
    repo = get_approval_repo()
    links = repo.list_all(status=status)
    if project_id is not None:
        from app.persistence.run_repository import get_run_repo

        run_repo = get_run_repo()
        runs = await run_repo.list(project_id=project_id, limit=100)
        allowed_run_ids = {run.run_id for run in runs}
        links = [l for l in links if l.run_id in allowed_run_ids]
    response = ApprovalsListResponse(approvals=[_to_summary(l) for l in links])
    return ApiResponse.success("", response.model_dump(mode="json"))


@decision_router.get("/approvals/{approval_id}")
def get_approval(approval_id: str) -> ApiResponse:
    """단일 approval 상세 조회 (issue #394)."""
    repo = get_approval_repo()
    link = repo.get(approval_id)
    if link is None:
        return ApiResponse.failure(
            "",
            ErrorCode.APPROVAL_NOT_FOUND,
            f"approval not found: {approval_id}",
        )
    return ApiResponse.success("", _to_summary(link).model_dump(mode="json"))
