"""#887 RCA gold set API + #888 AC@k 평가 리포트 엔드포인트."""
from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter

from app.persistence.gold_set_repository import get_gold_set_repo
from app.schemas import ApiResponse, ErrorCode
from app.schemas.gold_set import (
    LABELING_GUIDE,
    LABELING_RULES,
    CreateGoldSetEntryRequest,
    GoldSetEntry,
    GoldSetLabel,
    LabelCategory,
    ReviewGoldSetEntryRequest,
    ReviewStatus,
)

router = APIRouter()


def _entry_id() -> str:
    return f"gs_{uuid.uuid4().hex[:12]}"


def _request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


@router.post("/gold-set")
async def create_gold_set_entry(req: CreateGoldSetEntryRequest) -> ApiResponse:
    request_id = _request_id()
    repo = get_gold_set_repo()

    labels = [
        GoldSetLabel(
            label_id=f"lbl_{uuid.uuid4().hex[:8]}",
            category=LabelCategory(l["category"]),
            value=l["value"],
            evidence_ids=l.get("evidence_ids", []),
            notes=l.get("notes"),
        )
        for l in req.labels
    ]

    entry = GoldSetEntry(
        entry_id=_entry_id(),
        incident_id=req.incident_id,
        accepted_root_cause_id=req.accepted_root_cause_id,
        trigger=req.trigger,
        symptom=req.symptom,
        contributing_factors=req.contributing_factors,
        evidence_ids=req.evidence_ids,
        human_verdict=req.human_verdict,
        labels=labels,
    )
    await repo.create(entry)
    return ApiResponse.success(request_id, entry.model_dump(mode="json"))


@router.get("/gold-set")
async def list_gold_set_entries(
    review_status: str | None = None,
    root_cause_id: str | None = None,
    limit: int = 100,
) -> ApiResponse:
    request_id = _request_id()
    repo = get_gold_set_repo()
    status = ReviewStatus(review_status) if review_status else None
    entries = await repo.list(
        review_status=status, root_cause_id=root_cause_id, limit=limit
    )
    return ApiResponse.success(
        request_id,
        {
            "entries": [e.model_dump(mode="json") for e in entries],
            "total": len(entries),
        },
    )


@router.get("/gold-set/{entry_id}")
async def get_gold_set_entry(entry_id: str) -> ApiResponse:
    request_id = _request_id()
    entry = await get_gold_set_repo().get(entry_id)
    if entry is None:
        return ApiResponse.failure(
            request_id, ErrorCode.RUN_NOT_FOUND, f"gold set entry not found: {entry_id}"
        )
    return ApiResponse.success(request_id, entry.model_dump(mode="json"))


@router.patch("/gold-set/{entry_id}/review")
async def review_gold_set_entry(
    entry_id: str, req: ReviewGoldSetEntryRequest
) -> ApiResponse:
    request_id = _request_id()
    updated = await get_gold_set_repo().update_review(
        entry_id,
        review_status=req.review_status,
        reviewed_by=req.reviewed_by,
        human_verdict=req.human_verdict,
    )
    if updated is None:
        return ApiResponse.failure(
            request_id, ErrorCode.RUN_NOT_FOUND, f"gold set entry not found: {entry_id}"
        )
    return ApiResponse.success(request_id, updated.model_dump(mode="json"))


@router.delete("/gold-set/{entry_id}")
async def delete_gold_set_entry(entry_id: str) -> ApiResponse:
    request_id = _request_id()
    deleted = await get_gold_set_repo().delete(entry_id)
    return ApiResponse.success(request_id, {"entry_id": entry_id, "deleted": deleted})


@router.get("/gold-set-stats")
async def gold_set_stats() -> ApiResponse:
    request_id = _request_id()
    repo = get_gold_set_repo()
    total = await repo.count()
    reviewed = await repo.count(review_status=ReviewStatus.REVIEWED)
    draft = await repo.count(review_status=ReviewStatus.DRAFT)
    disputed = await repo.count(review_status=ReviewStatus.DISPUTED)
    return ApiResponse.success(request_id, {
        "total": total,
        "reviewed": reviewed,
        "draft": draft,
        "disputed": disputed,
    })


@router.get("/gold-set-labeling-guide")
async def labeling_guide() -> ApiResponse:
    request_id = _request_id()
    return ApiResponse.success(request_id, {
        "categories": LABELING_GUIDE,
        "rules": LABELING_RULES,
    })


# ── #888 AC@k/Avg@k 평가 리포트 ─────────────────────────────────────────────

from pydantic import BaseModel, Field as PydanticField


class RunEvalReportRequest(BaseModel):
    predictions: dict[str, list[dict[str, Any]]] = PydanticField(
        default_factory=dict,
        description="incident_id → [{'root_cause_id': ..., 'confidence': ...}, ...]",
    )


@router.post("/eval/accuracy-report")
async def run_accuracy_report(req: RunEvalReportRequest) -> ApiResponse:
    """#888 gold set 기반 AC@k/Avg@k 평가 리포트를 생성한다.

    predictions가 비어 있으면 reviewed gold set만으로 "예측 없음" 리포트를 반환한다.
    """
    from app.evaluation.offline_eval import report_to_dict, run_offline_eval

    request_id = _request_id()
    repo = get_gold_set_repo()
    entries = await repo.list(review_status=ReviewStatus.REVIEWED, limit=500)
    if not entries:
        return ApiResponse.failure(
            request_id, ErrorCode.VALIDATION_FAILED, "no reviewed gold set entries"
        )
    report = run_offline_eval(entries, req.predictions)
    return ApiResponse.success(request_id, report_to_dict(report))
