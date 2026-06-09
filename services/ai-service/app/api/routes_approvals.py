"""Approval Gate API — 승인·거절 엔드포인트."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.persistence.approval_link_repository import get_approval_repo
from app.schemas import ApiResponse, ErrorCode

router = APIRouter()
decision_router = APIRouter()


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
