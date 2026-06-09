"""Classifier agent tests for issue #278."""
from __future__ import annotations

import json

import pytest

from app.agents.classifier import run_classifier
from app.schemas.outputs import ClassifierOutput, RetrievalOutput
from app.schemas.state import EvidenceItem, EvidenceType, IncidentScope


class _DummyLLMProvider:
    def __init__(self, response: str = "") -> None:
        self.response = response

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        return self.response


def _patch_llm(monkeypatch: pytest.MonkeyPatch, response: str = "") -> None:
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: _DummyLLMProvider(response))


def _retrieval_with_summary(summary: str, evidence_id: str = "ev1") -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id=evidence_id,
                type=EvidenceType.PIPELINE_LOG,
                store_ref=f"evidence://run/{evidence_id}",
                summary=summary,
            )
        ]
    )


@pytest.mark.asyncio
async def test_lag_keyword_classifies_consumer_lag_spike(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)

    result = await run_classifier("consumer lag 급증", None)

    top = result.classification.incident_types[0]
    assert top.type == "CONSUMER_LAG_SPIKE"
    assert top.confidence > 0.6


@pytest.mark.asyncio
async def test_connector_failed_log_evidence(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    retrieval = _retrieval_with_summary("Kafka Connect connector task FAILED log evidence summary", "ev-connect")

    result = await run_classifier("connector 상태 확인", retrieval)

    top = result.classification.incident_types[0]
    assert top.type == "CONNECTOR_TASK_FAILED"
    assert "ev-connect" in top.evidence_ids


@pytest.mark.asyncio
async def test_unknown_when_no_signal(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)

    result = await run_classifier("오늘 처리 현황 알려줘", None)

    top = result.classification.incident_types[0]
    assert top.type == "UNKNOWN_NEEDS_MORE_EVIDENCE"
    assert top.confidence < 0.6


@pytest.mark.asyncio
async def test_catalog_only_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(
        monkeypatch,
        json.dumps(
            {
                "incident_types": [{"type": "MADE_UP_INCIDENT", "confidence": 0.99}],
                "incident_scope": "single",
                "needs_incident_group_analysis": False,
            }
        ),
    )

    result = await run_classifier("분류할 증거가 부족함", None)

    assert [item.type for item in result.classification.incident_types] == [
        "UNKNOWN_NEEDS_MORE_EVIDENCE"
    ]


@pytest.mark.asyncio
async def test_scope_incident_group(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    retrieval = RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev-a",
                type=EvidenceType.METRIC,
                store_ref="evidence://run/ev-a",
                summary="pipeline alpha consumer lag p95 증가",
            ),
            EvidenceItem(
                evidence_id="ev-b",
                type=EvidenceType.METRIC,
                store_ref="evidence://run/ev-b",
                summary="pipeline beta consumer lag p95 증가",
            ),
        ]
    )

    result = await run_classifier("여러 파이프라인 lag 확인", retrieval)

    assert result.classification.incident_scope == IncidentScope.INCIDENT_GROUP
    assert result.classification.needs_incident_group_analysis is True


@pytest.mark.asyncio
async def test_llm_unavailable_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, "")

    result = await run_classifier("consumer lag 급증", None)

    assert result.classification.incident_types[0].type == "CONSUMER_LAG_SPIKE"
    ClassifierOutput.model_validate(result.model_dump())


@pytest.mark.asyncio
async def test_schema_extra_forbid(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)

    result = await run_classifier("consumer lag 급증", None)

    ClassifierOutput.model_validate(result.model_dump())
