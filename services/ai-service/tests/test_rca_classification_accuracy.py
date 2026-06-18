"""#767 — RCA must classify the live 6 failure types to the correct top
root_cause_id, including when evidence summaries are Korean operations-backend
phrasings (e.g. ``DB 인증 실패``) rather than raw English log tokens.

Regression guard for the accuracy gap where SOURCE_AUTH_EXPIRED,
SINK_AUTH_EXPIRED, SOURCE_NETWORK_REACHABILITY and SCHEMA_MISMATCH fell through
to UNKNOWN because the evidence matrix only matched English tokens.
"""
from __future__ import annotations

import pytest

from app.agents.classifier import run_classifier
from app.agents.rca import run_rca
from app.schemas.outputs import (
    Classification,
    ClassifierOutput,
    IncidentTypeOutput,
    RetrievalOutput,
)
from app.schemas.state import EvidenceItem, EvidenceType, IncidentScope


class _DummyLLMProvider:
    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        # No LLM tie-break available; RCA must succeed on catalog evidence alone.
        return ""


@pytest.fixture(autouse=True)
def _patch_llm(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: _DummyLLMProvider())


def _classifier(incident_type: str) -> ClassifierOutput:
    return ClassifierOutput(
        classification=Classification(
            incident_scope=IncidentScope.SINGLE,
            incident_types=[
                IncidentTypeOutput(type=incident_type, confidence=0.9, evidence_ids=[])
            ],
        )
    )


def _retrieval(*summaries: str) -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id=f"ev-{index}",
                type=EvidenceType.PIPELINE_LOG,
                store_ref=f"evidence://run/ev-{index}",
                summary=summary,
            )
            for index, summary in enumerate(summaries, start=1)
        ]
    )


def _retrieval_items(*items: tuple[str, EvidenceType]) -> RetrievalOutput:
    return RetrievalOutput(
        evidence_items=[
            EvidenceItem(
                evidence_id=f"ev-{index}",
                type=evidence_type,
                store_ref=f"evidence://run/ev-{index}",
                summary=summary,
            )
            for index, (summary, evidence_type) in enumerate(items, start=1)
        ]
    )


# (incident_type fed to classifier, evidence summaries as they realistically
# reach RCA, expected top root_cause_id).
_CASES = {
    "CONNECTOR_TASK_FAILED": (
        "CONNECTOR_TASK_FAILED",
        ("connector task status FAILED", "task trace worker log exception 커넥터 오류"),
        "CONNECTOR_TASK_FAILED",
    ),
    "CONSUMER_LAG_SPIKE": (
        "CONSUMER_LAG_SPIKE",
        ("consumer lag 급증 lag p95 증가", "offset progression 둔화 commit rate 감소"),
        "CONSUMER_LAG_SPIKE",
    ),
    # Korean operations-backend summary (ConnectorErrorMessages.summarize):
    "SOURCE_AUTH_EXPIRED": (
        "SOURCE_AUTH_FAILURE",
        ("DB 인증 실패 (사용자·비밀번호 확인)",),
        "SOURCE_AUTH_EXPIRED",
    ),
    "SINK_AUTH_EXPIRED": (
        "SINK_AUTH_FAILURE",
        ("sink DB 인증 실패 (사용자·비밀번호 확인) 권한 거부",),
        "SINK_AUTH_EXPIRED",
    ),
    "SCHEMA_MISMATCH": (
        "SCHEMA_MISMATCH",
        ("스키마 불일치 deserialization error incompatible schema",),
        "SCHEMA_MISMATCH",
    ),
    "SOURCE_NETWORK_REACHABILITY": (
        "SOURCE_CONNECTION_TIMEOUT",
        ("DB 연결 실패 (호스트·포트·네트워크 확인) connection refused",),
        "SOURCE_NETWORK_REACHABILITY",
    ),
}


@pytest.mark.parametrize("case_name", list(_CASES))
@pytest.mark.asyncio
async def test_failure_type_classifies_to_expected_root_cause(case_name: str) -> None:
    incident_type, summaries, expected = _CASES[case_name]
    result = await run_rca(_classifier(incident_type), _retrieval(*summaries))

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == expected, (
        f"{case_name}: expected {expected}, got {top.root_cause_id} "
        f"(candidates={[c.root_cause_id for c in result.root_cause_candidates]})"
    )
    assert top.confidence >= 0.60


@pytest.mark.asyncio
async def test_at_least_four_of_six_types_correct() -> None:
    correct = 0
    for incident_type, summaries, expected in _CASES.values():
        result = await run_rca(_classifier(incident_type), _retrieval(*summaries))
        if result.root_cause_candidates[0].root_cause_id == expected:
            correct += 1
    assert correct >= 4, f"only {correct}/6 failure types classified correctly"


_RAW_E2_REGRESSIONS = {
    "SOURCE_AUTH_EXPIRED_02": (
        (
            "project_id=f537507e-9038-4df4-94b3-fde9e18eff3e. mode=incident_analysis. "
            "remediation_requested=false. Only identify the root cause id from evidence; "
            "do not propose or execute remediation. Source connector read stage reports "
            "permission denied and token expired for source credential. Secret rotation "
            "history shows source credential changed in this window. Network and sink "
            "writes are normal."
        ),
        (),
        "SOURCE_AUTH_EXPIRED",
    ),
    "SCHEMA_MISMATCH_03": (
        (
            "project_id=f537507e-9038-4df4-94b3-fde9e18eff3e. mode=incident_analysis. "
            "remediation_requested=false. Only identify the root cause id from evidence; "
            "do not propose or execute remediation. Evidence points to schema compatibility "
            "failure: serialization error and incompatible schema, recent subject version "
            "update, sample payload has field type mismatch. Sink is reachable and "
            "credentials valid."
        ),
        (
            "[catalog] Catalog - Evidence Matrix: write latency 증가 | Required | write p95 증가",
            "[catalog] Catalog - Evidence Matrix: sink auth/permission error log | Required",
        ),
        "SCHEMA_MISMATCH",
    ),
}


@pytest.mark.parametrize("case_name", list(_RAW_E2_REGRESSIONS))
@pytest.mark.asyncio
async def test_raw_e2_user_evidence_drives_classifier_and_rca(case_name: str) -> None:
    user_message, knowledge_summaries, expected = _RAW_E2_REGRESSIONS[case_name]
    retrieval = _retrieval_items(
        (user_message, EvidenceType.SNAPSHOT),
        *[(summary, EvidenceType.KNOWLEDGE) for summary in knowledge_summaries],
    )

    classifier = await run_classifier(user_message, retrieval)
    result = await run_rca(classifier, retrieval)

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == expected, (
        f"{case_name}: expected {expected}, got {top.root_cause_id} "
        f"(classifier={[c.type for c in classifier.classification.incident_types]}, "
        f"candidates={[c.root_cause_id for c in result.root_cause_candidates]})"
    )
    assert top.confidence >= 0.60


@pytest.mark.asyncio
async def test_structured_change_and_log_summaries_satisfy_config_required_evidence() -> None:
    retrieval = _retrieval(
        "structured log evidence: config validation error 또는 invalid option log worker log "
        "class=config connector=orders-source task=0 count=2",
        "live change evidence: 1 changes from metadb live sources "
        "(types={CONNECTOR_CONFIG_CREATED=1}, 최근 pipeline/connector config 변경 evidence count=1)",
    )

    result = await run_rca(_classifier("CONNECTOR_TASK_FAILED"), retrieval)

    assert result.root_cause_candidates[0].root_cause_id == "PIPELINE_CONFIG_INVALID"
    assert result.root_cause_candidates[0].confidence >= 0.60


@pytest.mark.asyncio
async def test_snapshot_only_config_summary_does_not_satisfy_config_change_evidence() -> None:
    retrieval = _retrieval(
        "structured log evidence: config validation error 또는 invalid option log worker log "
        "class=config connector=orders-source task=0 count=2",
        "live change evidence: changes=1, types={CONNECTOR_CONFIG_SNAPSHOT=1}; "
        "KafkaConnector CR config snapshot for connector orders-source",
    )

    result = await run_rca(_classifier("CONNECTOR_TASK_FAILED"), retrieval)

    config_candidate = next(
        candidate
        for candidate in result.root_cause_candidates
        if candidate.root_cause_id == "PIPELINE_CONFIG_INVALID"
    )
    assert config_candidate.required_evidence_satisfied is False
    assert "최근 pipeline/connector config 변경" in config_candidate.evidence_gap


@pytest.mark.asyncio
async def test_structured_auth_log_summary_satisfies_auth_required_evidence() -> None:
    retrieval = _retrieval(
        "structured log evidence: auth/permission error log worker log class=auth "
        "stage=source connector=orders-source task=0 count=3"
    )

    result = await run_rca(_classifier("SOURCE_AUTH_FAILURE"), retrieval)

    assert result.root_cause_candidates[0].root_cause_id == "SOURCE_AUTH_EXPIRED"
    assert result.root_cause_candidates[0].confidence >= 0.60


@pytest.mark.asyncio
async def test_structured_reachability_log_summary_satisfies_network_required_evidence() -> None:
    retrieval = _retrieval(
        "structured log evidence: Bifrost에서 source endpoint reachability 실패 "
        "connection refused no route to host 네트워크 도달 실패 worker log "
        "class=timeout stage=source connector=orders-source count=2"
    )

    result = await run_rca(_classifier("SOURCE_CONNECTION_TIMEOUT"), retrieval)

    assert result.root_cause_candidates[0].root_cause_id == "SOURCE_NETWORK_REACHABILITY"
    assert result.root_cause_candidates[0].confidence >= 0.60
