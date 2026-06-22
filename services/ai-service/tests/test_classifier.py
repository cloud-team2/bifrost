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


class _CapturingLLMProvider(_DummyLLMProvider):
    def __init__(self, response: str = "") -> None:
        super().__init__(response)
        self.messages: list[dict] = []

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        self.messages = messages
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


def _retrieval_with_item(
    summary: str,
    evidence_type: EvidenceType,
    evidence_id: str = "ev1",
) -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id=evidence_id,
                type=evidence_type,
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
async def test_knowledge_catalog_text_is_not_observed_incident_signal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    retrieval = _retrieval_with_item(
        "[catalog] Evidence Matrix: sink write latency 증가 | Required | write p95 증가",
        EvidenceType.KNOWLEDGE,
        "ev-knowledge",
    )

    result = await run_classifier(
        "Evidence points to schema compatibility failure: serialization error and incompatible schema.",
        retrieval,
    )

    top = result.classification.incident_types[0]
    assert top.type == "SCHEMA_MISMATCH"
    assert top.evidence_ids == []


@pytest.mark.asyncio
async def test_unknown_when_no_signal(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)

    result = await run_classifier("오늘 처리 현황 알려줘", None)

    top = result.classification.incident_types[0]
    assert top.type == "UNKNOWN_NEEDS_MORE_EVIDENCE"
    assert top.confidence < 0.6


@pytest.mark.asyncio
async def test_control_metadata_does_not_drive_rule_classification(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    retrieval = _retrieval_with_summary(
        "expected_root_cause=SINK_AUTH_EXPIRED normal observation only",
        "ev-label",
    )

    result = await run_classifier("상태 확인", retrieval)

    assert [item.type for item in result.classification.incident_types] == [
        "UNKNOWN_NEEDS_MORE_EVIDENCE"
    ]


@pytest.mark.asyncio
async def test_control_metadata_is_removed_from_classifier_llm_prompt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider = _CapturingLLMProvider("")
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: provider)
    retrieval = _retrieval_with_summary(
        "accepted_root_cause_id=SINK_AUTH_EXPIRED normal observation only",
        "ev-label",
    )

    await run_classifier("expected_root_cause=SOURCE_AUTH_EXPIRED 상태 확인", retrieval)

    prompt = provider.messages[1]["content"]
    assert "expected_root_cause" not in prompt
    assert "accepted_root_cause_id" not in prompt
    assert "SOURCE_AUTH_EXPIRED" not in prompt
    assert "SINK_AUTH_EXPIRED" not in prompt


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
