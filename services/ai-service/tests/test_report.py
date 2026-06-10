"""Report agent tests for issue #451 — RCA·분류 결과가 최종 답변 입력에 반영되는지."""
from __future__ import annotations

import pytest

from app.agents.report import run_report
from app.schemas.outputs import (
    Classification,
    ClassifierOutput,
    IncidentTypeOutput,
    RcaOutput,
    RetrievalOutput,
)
from app.schemas.state import (
    AgentMode,
    EvidenceItem,
    EvidenceType,
    IncidentScope,
    RootCauseCandidate,
)


class _CapturingLLM:
    """generate() 에 전달된 messages 를 보관해 프롬프트 내용을 검증한다."""

    def __init__(self, response: str = "ok") -> None:
        self.response = response
        self.messages: list[dict] | None = None

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        self.messages = messages
        return self.response

    def user_content(self) -> str:
        assert self.messages is not None, "generate() 가 호출되지 않았습니다"
        return next(m["content"] for m in self.messages if m["role"] == "user")

    def system_content(self) -> str:
        assert self.messages is not None
        return next(m["content"] for m in self.messages if m["role"] == "system")


def _retrieval() -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id="ev-1",
                type=EvidenceType.METRIC,
                store_ref="metrics://lag",
                summary="consumer lag p95 가 10분간 3배 증가",
            )
        ]
    )


def _classifier() -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[
                IncidentTypeOutput(type="CONSUMER_LAG_SPIKE", confidence=0.9, evidence_ids=["ev-1"])
            ],
        )
    )


def _rca(
    *,
    root_cause_id: str = "RECENT_DEPLOY_REGRESSION",
    confidence: float = 0.86,
    required_evidence_satisfied: bool = True,
    evidence_gap: list[str] | None = None,
) -> RcaOutput:
    return RcaOutput(
        root_cause_candidates=[
            RootCauseCandidate(
                root_cause_id=root_cause_id,
                confidence=confidence,
                required_evidence_satisfied=required_evidence_satisfied,
                supporting_evidence_ids=["ev-1"],
                evidence_gap=evidence_gap or [],
                explanation="최근 배포 직후 lag 가 급증했고 offset 진행이 정체됨",
            )
        ]
    )


@pytest.mark.asyncio
async def test_report_prompt_includes_rca_root_cause_and_confidence():
    llm = _CapturingLLM()
    await run_report(
        "왜 lag 가 늘었어?",
        _retrieval(),
        AgentMode.INCIDENT_ANALYSIS,
        llm,
        rca_out=_rca(),
        classifier_out=_classifier(),
    )
    prompt = llm.user_content()
    assert "RECENT_DEPLOY_REGRESSION" in prompt
    assert "0.86" in prompt
    assert "최근 배포 직후" in prompt
    assert "ev-1" in prompt
    # 분류 결과도 진단 컨텍스트로 포함
    assert "CONSUMER_LAG_SPIKE" in prompt
    # 시스템 프롬프트에 catalog 밖 생성 금지 지침
    assert "후보 밖" in llm.system_content()


@pytest.mark.asyncio
async def test_report_flags_uncertainty_on_evidence_gap():
    llm = _CapturingLLM()
    await run_report(
        "왜 lag 가 늘었어?",
        _retrieval(),
        AgentMode.INCIDENT_ANALYSIS,
        llm,
        rca_out=_rca(confidence=0.3, required_evidence_satisfied=False, evidence_gap=["offset_metric"]),
        classifier_out=_classifier(),
    )
    prompt = llm.user_content()
    assert "evidence gap" in prompt
    assert "offset_metric" in prompt
    assert "escalation" in prompt or "불확실" in prompt


@pytest.mark.asyncio
async def test_report_unknown_root_cause_flags_uncertainty():
    llm = _CapturingLLM()
    await run_report(
        "왜 lag 가 늘었어?",
        _retrieval(),
        AgentMode.INCIDENT_ANALYSIS,
        llm,
        rca_out=_rca(root_cause_id="UNKNOWN", confidence=0.9, required_evidence_satisfied=True),
    )
    assert "escalation" in llm.user_content() or "불확실" in llm.user_content()


@pytest.mark.asyncio
async def test_report_without_rca_is_evidence_only():
    llm = _CapturingLLM()
    await run_report(
        "현재 파이프라인 상태 알려줘",
        _retrieval(),
        AgentMode.SIMPLE_QUERY,
        llm,
    )
    prompt = llm.user_content()
    assert "consumer lag p95" in prompt
    assert "진단 결과" not in prompt
    assert "후보 밖" not in llm.system_content()
