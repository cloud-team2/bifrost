"""#887 RCA gold set API + #888 AC@k 평가 리포트 엔드포인트."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
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
    PromoteGoldSetRequest,
    PromoteVerdict,
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


@router.post("/gold-set/promote")
async def promote_gold_set_entry(req: PromoteGoldSetRequest) -> ApiResponse:
    """#982 운영자 RCA 평결을 gold set 으로 승격한다.

    operations-backend 의 RCA 피드백(채택/거부/수정)이 호출하는 진입점.
    - accepted/corrected: 정답(accepted_root_cause_id)이 확정되므로 reviewed 행으로 기록한다.
    - rejected: 정답을 모르므로 disputed 로 기록한다(AC@k/ECE 평가에서 제외).
    같은 incident 의 기존 항목(예: backfill 로 적재된 unreviewed 예측)이 있으면 갱신하고,
    없으면 새 항목을 생성한다(멱등 upsert).
    """
    request_id = _request_id()
    repo = get_gold_set_repo()

    if req.verdict == PromoteVerdict.CORRECTED and not (req.corrected_root_cause_id or "").strip():
        return ApiResponse.failure(
            request_id,
            ErrorCode.VALIDATION_FAILED,
            "corrected verdict requires corrected_root_cause_id",
        )

    if req.verdict == PromoteVerdict.ACCEPTED:
        accepted = (req.predicted_root_cause_id or "").strip() or None
        review_status = ReviewStatus.REVIEWED
        if accepted is None:
            return ApiResponse.failure(
                request_id,
                ErrorCode.VALIDATION_FAILED,
                "accepted verdict requires predicted_root_cause_id to accept",
            )
    elif req.verdict == PromoteVerdict.CORRECTED:
        accepted = req.corrected_root_cause_id.strip()
        review_status = ReviewStatus.REVIEWED
    else:  # REJECTED — 예측이 틀렸으나 정답 미상. 평가셋 제외(disputed).
        accepted = None
        review_status = ReviewStatus.DISPUTED

    predicted = (req.predicted_root_cause_id or "").strip() or None

    existing = await repo.find_latest_by_incident(req.incident_id)
    if existing is not None:
        updated = await repo.set_verdict(
            existing.entry_id,
            review_status=review_status,
            reviewed_by=req.reviewed_by,
            human_verdict=req.verdict.value,
            accepted_root_cause_id=accepted,
        )
        return ApiResponse.success(request_id, updated.model_dump(mode="json"))

    labels: list[GoldSetLabel] = []
    if accepted is not None:
        labels.append(
            GoldSetLabel(
                label_id=f"lbl_{uuid.uuid4().hex[:8]}",
                category=LabelCategory.ROOT_CAUSE,
                value=accepted,
                notes=f"operator {req.verdict.value}",
            )
        )
    entry = GoldSetEntry(
        entry_id=_entry_id(),
        incident_id=req.incident_id,
        accepted_root_cause_id=accepted,
        predicted_root_cause_id=predicted,
        trigger=req.trigger,
        symptom=req.symptom,
        human_verdict=req.verdict.value,
        labels=labels,
        review_status=review_status,
        reviewed_by=req.reviewed_by,
        reviewed_at=datetime.now(timezone.utc),
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


# ── #889 ECE confidence 캘리브레이션 ─────────────────────────────────────────


@router.post("/eval/calibration-report")
async def run_calibration_report(req: RunEvalReportRequest) -> ApiResponse:
    """#889 confidence bin별 accuracy/gap/ECE 리포트를 생성한다.

    과신 구간을 식별하고 confidence cap / UNKNOWN threshold 재설정 근거를 산출한다.
    """
    from app.evaluation.calibration import calibration_report_to_dict, compute_calibration
    from app.evaluation.offline_eval import build_eval_cases_from_gold_set

    request_id = _request_id()
    repo = get_gold_set_repo()
    entries = await repo.list(review_status=ReviewStatus.REVIEWED, limit=500)
    if not entries:
        return ApiResponse.failure(
            request_id, ErrorCode.VALIDATION_FAILED, "no reviewed gold set entries"
        )
    cases = build_eval_cases_from_gold_set(entries, req.predictions)
    report = compute_calibration(cases)
    return ApiResponse.success(request_id, calibration_report_to_dict(report))
