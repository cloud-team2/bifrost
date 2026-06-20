"""#884 threshold governance — 임계값 레지스트리.

코드 상수·DB 기본값으로 흩어진 임계값을 버전 관리되는 운영 파라미터로 통합한다.
각 임계값은 이름·값·버전·근거·owner·보정 시각·평가셋 버전·rollback 값을 갖는다.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ThresholdEntry(BaseModel):
    model_config = ConfigDict(extra="forbid")

    threshold_name: str
    value: float
    version: int = 1
    basis: str
    owner: str
    last_calibrated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    dataset_version: str = "none"
    rollback_value: float | None = None
    category: str = "rca"
    description: str = ""


class ThresholdUpdateRequest(BaseModel):
    value: float
    basis: str
    owner: str
    dataset_version: str = "none"


_INITIAL_THRESHOLDS: list[dict[str, Any]] = [
    {
        "threshold_name": "min_confident_root_cause",
        "value": 0.60,
        "basis": "RCA 후보 최소 confidence. 이하이면 UNKNOWN_WITH_EVIDENCE_GAP 분류.",
        "owner": "ai-service/rca",
        "category": "rca",
        "description": "RCA 후보를 유의미하다고 판단하는 최소 confidence",
    },
    {
        "threshold_name": "llm_tie_margin",
        "value": 0.10,
        "basis": "상위 2개 후보 confidence 차이가 이 값 이하이면 LLM tie-break 호출.",
        "owner": "ai-service/rca",
        "category": "rca",
        "description": "LLM tie-break 호출 기준 마진",
    },
    {
        "threshold_name": "default_confidence_cap",
        "value": 0.79,
        "basis": "카탈로그 root cause별 기본 confidence 상한(ECE 보정 전).",
        "owner": "ai-service/catalog",
        "category": "rca",
        "description": "root cause 기본 confidence 상한",
    },
    {
        "threshold_name": "min_confidence_for_action",
        "value": 0.80,
        "basis": "조치 실행 허용 최소 confidence. 이하이면 더 많은 증거 수집 권고.",
        "owner": "ai-service/catalog",
        "category": "rca",
        "description": "조치 실행 허용 최소 confidence",
    },
    {
        "threshold_name": "error_rate_warning",
        "value": 0.5,
        "basis": "파이프라인 에러율(%) warning 임계값. Spring PipelineStatusService.",
        "owner": "operations-backend/pipeline",
        "category": "alerting",
        "description": "파이프라인 에러율 warning 임계값(%)",
    },
    {
        "threshold_name": "error_rate_critical",
        "value": 2.0,
        "basis": "파이프라인 에러율(%) critical 임계값. Spring PipelineStatusService.",
        "owner": "operations-backend/pipeline",
        "category": "alerting",
        "description": "파이프라인 에러율 critical 임계값(%)",
    },
    {
        "threshold_name": "lag_warning",
        "value": 5000.0,
        "basis": "consumer lag warning 임계값. WorkspaceSettings 기본값.",
        "owner": "operations-backend/workspace",
        "category": "alerting",
        "description": "consumer lag warning 임계값",
    },
    {
        "threshold_name": "lag_critical",
        "value": 50000.0,
        "basis": "consumer lag critical 임계값. WorkspaceSettings 기본값.",
        "owner": "operations-backend/workspace",
        "category": "alerting",
        "description": "consumer lag critical 임계값",
    },
    {
        "threshold_name": "rca_embedding_match_threshold",
        "value": 0.86,
        "basis": "RCA 증거 매칭 시 embedding similarity 최소 점수.",
        "owner": "ai-service/rca",
        "category": "rca",
        "description": "증거 embedding 매칭 최소 점수",
    },
    {
        "threshold_name": "unknown_abstain_threshold",
        "value": 0.60,
        "basis": "confidence가 이 값 미만이면 UNKNOWN으로 abstain. min_confident_root_cause와 동일.",
        "owner": "ai-service/rca",
        "category": "rca",
        "description": "UNKNOWN abstain 기준 confidence",
    },
]


class InMemoryThresholdRegistry:
    def __init__(self) -> None:
        self._store: dict[str, ThresholdEntry] = {}
        self._history: dict[str, list[ThresholdEntry]] = {}
        self._initialize()

    def _initialize(self) -> None:
        for t in _INITIAL_THRESHOLDS:
            entry = ThresholdEntry(**t)
            self._store[entry.threshold_name] = entry
            self._history.setdefault(entry.threshold_name, []).append(entry)

    def get(self, threshold_name: str) -> ThresholdEntry | None:
        return self._store.get(threshold_name)

    def get_value(self, threshold_name: str, default: float | None = None) -> float:
        entry = self._store.get(threshold_name)
        if entry is None:
            if default is not None:
                return default
            raise KeyError(f"threshold not found: {threshold_name}")
        return entry.value

    def list(self, *, category: str | None = None) -> list[ThresholdEntry]:
        entries = list(self._store.values())
        if category:
            entries = [e for e in entries if e.category == category]
        return sorted(entries, key=lambda e: e.threshold_name)

    def update(
        self,
        threshold_name: str,
        value: float,
        basis: str,
        owner: str,
        dataset_version: str = "none",
    ) -> ThresholdEntry | None:
        current = self._store.get(threshold_name)
        if current is None:
            return None
        updated = current.model_copy(update={
            "rollback_value": current.value,
            "value": value,
            "version": current.version + 1,
            "basis": basis,
            "owner": owner,
            "dataset_version": dataset_version,
            "last_calibrated_at": datetime.now(timezone.utc),
        })
        self._store[threshold_name] = updated
        self._history.setdefault(threshold_name, []).append(updated)
        return updated

    def rollback(self, threshold_name: str) -> ThresholdEntry | None:
        current = self._store.get(threshold_name)
        if current is None or current.rollback_value is None:
            return None
        rolled_back = current.model_copy(update={
            "value": current.rollback_value,
            "rollback_value": current.value,
            "version": current.version + 1,
            "basis": f"rollback from v{current.version}",
            "last_calibrated_at": datetime.now(timezone.utc),
        })
        self._store[threshold_name] = rolled_back
        self._history.setdefault(threshold_name, []).append(rolled_back)
        return rolled_back

    def history(self, threshold_name: str) -> list[ThresholdEntry]:
        return list(self._history.get(threshold_name, []))


_registry = InMemoryThresholdRegistry()


def get_threshold_registry() -> InMemoryThresholdRegistry:
    return _registry
