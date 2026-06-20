"""#887 RCA gold set — 라벨링 프로토콜 및 평가셋 스키마.

resolved incident 기반 정답 라벨을 저장·관리한다.
trigger/symptom/root_cause/contributing_factor 구분 기준은
docs/design/rca-standards-review.md §4.3, §7-6, §8 참고.
"""
from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class LabelCategory(str, Enum):
    TRIGGER = "trigger"
    SYMPTOM = "symptom"
    ROOT_CAUSE = "root_cause"
    CONTRIBUTING_FACTOR = "contributing_factor"


class ReviewStatus(str, Enum):
    DRAFT = "draft"
    REVIEWED = "reviewed"
    DISPUTED = "disputed"


class GoldSetLabel(StrictModel):
    label_id: str
    category: LabelCategory
    value: str
    evidence_ids: list[str] = Field(default_factory=list)
    notes: str | None = None


class GoldSetEntry(StrictModel):
    entry_id: str
    incident_id: str
    accepted_root_cause_id: str
    trigger: str | None = None
    symptom: str | None = None
    contributing_factors: list[str] = Field(default_factory=list)
    evidence_ids: list[str] = Field(default_factory=list)
    human_verdict: str | None = None
    labels: list[GoldSetLabel] = Field(default_factory=list)
    review_status: ReviewStatus = ReviewStatus.DRAFT
    reviewed_by: str | None = None
    reviewed_at: datetime | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @field_validator("accepted_root_cause_id")
    @classmethod
    def _root_cause_not_empty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("accepted_root_cause_id must not be empty")
        return v


class CreateGoldSetEntryRequest(BaseModel):
    incident_id: str
    accepted_root_cause_id: str
    trigger: str | None = None
    symptom: str | None = None
    contributing_factors: list[str] = Field(default_factory=list)
    evidence_ids: list[str] = Field(default_factory=list)
    human_verdict: str | None = None
    labels: list[dict[str, Any]] = Field(default_factory=list)


class ReviewGoldSetEntryRequest(BaseModel):
    review_status: ReviewStatus
    reviewed_by: str
    human_verdict: str | None = None


LABELING_GUIDE = {
    "trigger": "장애 발생 직전 수행된 변경·이벤트. 시간 선행성이 필수이며, 제거 시 장애가 발생하지 않았을 조건.",
    "symptom": "장애로 인해 관측된 현상(에러 로그, 메트릭 이상 등). 원인이 아닌 결과에 해당.",
    "root_cause": "제거하면 장애가 재발하지 않는 근본 원인. 카탈로그 root_cause_id와 1:1 매핑.",
    "contributing_factor": "단독으로 장애를 유발하지 않지만, trigger/root_cause와 결합해 장애를 악화시킨 조건.",
}

LABELING_RULES = [
    "하나의 incident에 root_cause는 정확히 1개 (accepted_root_cause_id).",
    "trigger와 root_cause가 같을 수 있다 (예: credential 만료가 trigger이자 root cause).",
    "symptom은 여러 개 가능 (lag 급증 + task FAILED 동시 관측).",
    "contributing_factor는 0~N개 (예: 리소스 부족이 장애를 악화).",
    "evidence_ids는 판단 근거가 된 증거 ID 목록으로, 추후 AC@k 평가 시 증거 재현에 사용.",
    "review_status가 'reviewed'인 항목만 평가(AC@k, ECE)에 사용한다.",
]
