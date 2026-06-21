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
    # #962 sink DB down: 커넥터 task 는 FAILED(근접 증상)지만 trace 의 connection refused 가
    # 진짜 원인(sink dependency 연결 실패)을 가리킨다. 심층 원인이 top 이어야 한다.
    "SINK_DB_DOWN": (
        "CONNECTOR_TASK_FAILED",
        (
            "sink connector task status FAILED",
            "java.net.ConnectException: Connection refused host=tenant-mariadb-service:3306",
        ),
        "SINK_DB_CONNECTION_TIMEOUT",
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
async def test_sink_db_down_root_cause_outranks_connector_task_failed_symptom() -> None:
    """#962 sink DB down(Connection refused)이면 RCA top 은 근접 증상 CONNECTOR_TASK_FAILED 가
    아니라 심층 원인 SINK_DB_CONNECTION_TIMEOUT 여야 한다. 증상은 사라지지 않고 그 아래로 강등된다."""
    result = await run_rca(
        _classifier("CONNECTOR_TASK_FAILED"),
        _retrieval(
            "sink connector task status FAILED",
            "java.net.ConnectException: Connection refused host=tenant-mariadb-service:3306",
        ),
    )
    ids = [c.root_cause_id for c in result.root_cause_candidates]
    assert ids[0] == "SINK_DB_CONNECTION_TIMEOUT", ids
    assert "CONNECTOR_TASK_FAILED" in ids, ids  # 증상은 2차 후보로 보존
    assert ids.index("SINK_DB_CONNECTION_TIMEOUT") < ids.index("CONNECTOR_TASK_FAILED")


@pytest.mark.asyncio
async def test_connector_task_failed_stays_top_without_deeper_cause() -> None:
    """#962 회귀 가드: 심층 원인(연결 실패/timeout 등)이 입증되지 않은 일반 task 실패는
    강등되지 않고 여전히 CONNECTOR_TASK_FAILED 가 top 이어야 한다."""
    result = await run_rca(
        _classifier("CONNECTOR_TASK_FAILED"),
        _retrieval("connector task status FAILED", "task trace worker log generic exception"),
    )
    assert result.root_cause_candidates[0].root_cause_id == "CONNECTOR_TASK_FAILED"


@pytest.mark.asyncio
async def test_at_least_four_of_six_types_correct() -> None:
    correct = 0
    for incident_type, summaries, expected in _CASES.values():
        result = await run_rca(_classifier(incident_type), _retrieval(*summaries))
        if result.root_cause_candidates[0].root_cause_id == expected:
            correct += 1
    assert correct >= 4, f"only {correct}/{len(_CASES)} failure types classified correctly"


@pytest.mark.asyncio
async def test_consumer_lag_spike_from_realistic_incident_evidence() -> None:
    """#957 라이브 회귀: lag 인시던트의 *실제* 증거(이벤트 제목·메시지 + 스냅샷)만으로도
    CONSUMER_LAG_SPIKE 를 산출해야 한다.

    라이브 테스트(2026-06-21)에서 RCA 가 UNKNOWN 으로 빠진 이유: 수집된 증거가
    'Consumer lag critical' + 'total_lag=60110' 스냅샷뿐이라 required 룰('consumer lag 급증')의
    *추세* 신호가 없었다. ops-backend lag 이벤트(edge-trigger 상승 전이)가 'spike/급증'을 담도록
    보강(#957)해, 스냅샷이 아닌 전이 근거로 추세 증거를 공급한다.
    """
    summaries = (
        "Consumer lag spike (critical): connect-5d4b0826-sink",          # 이벤트 제목(보강된 상승 전이 표현)
        "consumer lag 급증으로 임계 초과: group=connect-5d4b0826-sink lag=60110",  # 이벤트 메시지
        "consumer lag snapshot: total_lag=60110 partition_count=6",       # get_consumer_lag 스냅샷
    )
    result = await run_rca(_classifier("CONSUMER_LAG_SPIKE"), _retrieval(*summaries))
    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "CONSUMER_LAG_SPIKE", (
        f"expected CONSUMER_LAG_SPIKE, got {top.root_cause_id} "
        f"(candidates={[c.root_cause_id for c in result.root_cause_candidates]})"
    )
    assert top.confidence >= 0.60


@pytest.mark.asyncio
async def test_consumer_lag_snapshot_without_trend_stays_unknown() -> None:
    """#957 엄격성 보존: 추세 신호 없이 높은 lag 스냅샷(상관)만 있으면 RCA 는 단정하지 않고
    UNKNOWN 으로 abstain 해야 한다(temporality 원칙). '스냅샷=급증' 으로 인정하지 않는다 —
    이것이 보강 전 라이브에서 본 (올바른) 동작이며, 본 fix 는 추세 증거를 *공급* 할 뿐
    룰을 느슨하게 만들지 않는다."""
    summaries = (
        "consumer lag snapshot: total_lag=60110 partition_count=6",
        "Consumer lag critical: connect-5d4b0826-sink",  # 추세 단어 없는 과거 표현
    )
    result = await run_rca(_classifier("CONSUMER_LAG_SPIKE"), _retrieval(*summaries))
    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP", (
        f"snapshot-only correlation must abstain, got {top.root_cause_id}"
    )


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
