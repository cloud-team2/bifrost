"""RCA agent tests for issue #279."""
from __future__ import annotations

import json

import pytest

from app.agents.rca import run_rca
from app.catalogs.root_causes import root_cause_ids
from app.schemas.outputs import Classification, ClassifierOutput, IncidentTypeOutput, RcaOutput, RetrievalOutput
from app.schemas.state import EvidenceItem, EvidenceType, IncidentScope


class _DummyLLMProvider:
    def __init__(self, response: str = "") -> None:
        self.response = response

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        return self.response


def _patch_llm(monkeypatch: pytest.MonkeyPatch, response: str = "") -> None:
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: _DummyLLMProvider(response))


def _classifier(*incident_types: str) -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[
                IncidentTypeOutput(type=incident_type, confidence=0.9, evidence_ids=[])
                for incident_type in incident_types
            ],
        )
    )


def _retrieval(*summaries: str) -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id=f"ev-{index}",
                type=EvidenceType.METRIC if "latency" in summary or "lag" in summary else EvidenceType.PIPELINE_LOG,
                store_ref=f"evidence://run/ev-{index}",
                summary=summary,
            )
            for index, summary in enumerate(summaries, start=1)
        ]
    )


@pytest.mark.asyncio
async def test_source_db_timeout_full_evidence(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval(
            "source connection timeout 증가 pipeline_source_connection_timeout_total 증가",
            "pipeline extract/read 단계 timeout log ConnectionTimeout extract_users task",
            "pipeline read latency 증가 extract duration p95 증가",
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "SOURCE_DB_CONNECTION_TIMEOUT"
    assert top.required_evidence_satisfied is True
    assert top.confidence >= 0.80


@pytest.mark.asyncio
async def test_consumer_lag_spike_partial_evidence(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONSUMER_LAG_SPIKE"),
        _retrieval("consumer lag 급증 lag p95 증가"),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "CONSUMER_LAG_SPIKE"
    assert top.required_evidence_satisfied is False
    assert 0.60 <= top.confidence <= 0.79
    assert top.evidence_gap


@pytest.mark.asyncio
async def test_required_missing_caps_confidence(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval("unrelated deployment event without source timeout evidence"),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.required_evidence_satisfied is False
    assert top.evidence_gap
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_negative_evidence_penalty(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    positive = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval(
            "source connection timeout 증가 pipeline_source_connection_timeout_total 증가",
            "pipeline extract/read 단계 timeout log ConnectionTimeout extract_users task",
            "pipeline read latency 증가 extract duration p95 증가",
        ),
    )
    with_negative = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval(
            "source connection timeout 증가 pipeline_source_connection_timeout_total 증가",
            "pipeline extract/read 단계 timeout log ConnectionTimeout extract_users task",
            "pipeline read latency 증가 extract duration p95 증가",
            "sink write timeout 증가 source 단독 원인 가능성 낮춤",
        ),
    )

    top_positive = positive.root_cause_candidates[0]
    top_negative = with_negative.root_cause_candidates[0]
    assert top_negative.root_cause_id == "SOURCE_DB_CONNECTION_TIMEOUT"
    assert top_negative.negative_evidence_ids
    assert top_negative.confidence < top_positive.confidence


@pytest.mark.asyncio
async def test_classifier_unknown_passes_through(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("UNKNOWN_NEEDS_MORE_EVIDENCE"),
        _retrieval("consumer lag 급증 lag p95 증가"),
    )

    assert [item.root_cause_id for item in result.root_cause_candidates] == [
        "UNKNOWN_WITH_EVIDENCE_GAP"
    ]


@pytest.mark.asyncio
async def test_catalog_only_root_cause_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(
        monkeypatch,
        json.dumps(
            {
                "selected_root_cause_id": "MADE_UP_ROOT_CAUSE",
                "confidence": 0.99,
                "explanation": "invalid catalog id must be ignored",
            }
        ),
    )
    result = await run_rca(
        _classifier("CONSUMER_LAG_SPIKE"),
        _retrieval("consumer lag 급증 and sink write latency 증가"),
    )

    known_ids = set(root_cause_ids())
    assert all(item.root_cause_id in known_ids for item in result.root_cause_candidates)
    assert result.root_cause_candidates[0].root_cause_id != "MADE_UP_ROOT_CAUSE"


@pytest.mark.asyncio
async def test_empty_evidence_items(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(_classifier("SOURCE_CONNECTION_TIMEOUT"), RetrievalOutput(evidence_items=[]))

    assert [item.root_cause_id for item in result.root_cause_candidates] == [
        "UNKNOWN_WITH_EVIDENCE_GAP"
    ]


@pytest.mark.asyncio
async def test_llm_unavailable_rule_only(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch, "")
    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval(
            "source connection timeout 증가 pipeline_source_connection_timeout_total 증가",
            "pipeline extract/read 단계 timeout log ConnectionTimeout extract_users task",
            "pipeline read latency 증가 extract duration p95 증가",
        ),
    )

    assert result.root_cause_candidates[0].root_cause_id == "SOURCE_DB_CONNECTION_TIMEOUT"
    RcaOutput.model_validate(result.model_dump())


@pytest.mark.asyncio
async def test_schema_extra_forbid(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONSUMER_LAG_SPIKE"),
        _retrieval("consumer lag 급증 lag p95 증가"),
    )

    RcaOutput.model_validate(result.model_dump())
