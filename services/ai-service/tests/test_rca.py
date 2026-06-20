"""RCA agent tests for issue #279."""
from __future__ import annotations

import json

import pytest

from app.agents.rca import run_rca
from app.catalogs.evidence_matrix import get_evidence_profile
from app.catalogs.root_causes import root_cause_ids
from app.core.config import Settings, settings
from app.schemas.outputs import Classification, ClassifierOutput, IncidentTypeOutput, RcaOutput, RetrievalOutput
from app.schemas.state import EvidenceItem, EvidenceType, IncidentScope


class _DummyLLMProvider:
    def __init__(self, response: str = "") -> None:
        self.response = response

    async def generate(self, messages: list[dict], model: str | None = None) -> str:
        return self.response


def _patch_llm(monkeypatch: pytest.MonkeyPatch, response: str = "") -> None:
    monkeypatch.setattr("app.llm.provider.get_llm_provider", lambda: _DummyLLMProvider(response))


class _SemanticEmbedder:
    dimensions = 3

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [self._embed(text) for text in texts]

    async def embed_text(self, text: str) -> list[float]:
        return self._embed(text)

    def _embed(self, text: str) -> list[float]:
        normalized = text.casefold()
        if (
            "auth/permission error log" in normalized
            or "login was rejected" in normalized
            or "stored secret is stale" in normalized
        ):
            return [1.0, 0.0, 0.0]
        if (
            "source connection timeout 증가" in normalized
            or "pipeline extract/read 단계 timeout log" in normalized
            or "pipeline read latency 증가" in normalized
            or "upstream source dependency stopped responding" in normalized
            or "extract stage exceeded its read deadline" in normalized
            or "read duration breached the p95 threshold" in normalized
            or "alpha condition observed" in normalized
            or "beta condition observed" in normalized
            or "gamma condition observed" in normalized
        ):
            return [0.0, 1.0, 0.0]
        if (
            "source metric 정상" in normalized
            or "source timeout 후보 약화" in normalized
            or "source database metrics stayed healthy" in normalized
            or "delta condition observed" in normalized
        ):
            return [0.0, 0.0, 1.0]
        if (
            "pod last state oomkilled" in normalized
            or "container exceeded memory limit and was killed by kubernetes" in normalized
        ):
            return [1.0, 1.0, 0.0]
        return [0.0, 0.0, 0.0]


class _MalformedEmbedder:
    dimensions = 3

    def __init__(self, mode: str) -> None:
        self.mode = mode

    async def embed_texts(self, texts: list[str]) -> object:
        if self.mode == "none":
            return None
        if self.mode == "short":
            return [[1.0, 0.0, 0.0] for _ in texts[:-1]]
        if self.mode == "non_vector":
            return ["not-a-vector" for _ in texts]
        return [
            [1.0, 0.0, 0.0] if index % 2 == 0 else [1.0, 0.0]
            for index, _ in enumerate(texts)
        ]

    async def embed_text(self, text: str) -> list[float]:
        return [0.0, 0.0, 0.0]


def _patch_semantic_embedder(monkeypatch: pytest.MonkeyPatch, *, enabled: bool = True) -> None:
    monkeypatch.setattr(settings, "rca_embedding_match_enabled", enabled)
    monkeypatch.setattr(settings, "rca_embedding_match_threshold", 0.8)
    monkeypatch.setattr(settings, "rca_embedding_match_prefer_openai", False)
    monkeypatch.setattr("app.knowledge.embedder.get_embedder", lambda **_: _SemanticEmbedder())


def test_rca_embedding_match_defaults_to_guarded_off(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AI_RCA_EMBEDDING_MATCH_ENABLED", raising=False)
    monkeypatch.delenv("AI_RCA_EMBEDDING_MATCH_THRESHOLD", raising=False)
    monkeypatch.delenv("AI_RCA_EMBEDDING_MATCH_PREFER_OPENAI", raising=False)

    config = Settings(_env_file=None)

    assert config.rca_embedding_match_enabled is False
    assert config.rca_embedding_match_threshold == 0.86
    assert config.rca_embedding_match_prefer_openai is True


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
async def test_consumer_lag_snapshot_does_not_satisfy_trend_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONSUMER_LAG_SPIKE"),
        _retrieval(
            "consumer lag snapshot: total_lag=12345, lag p95=12345.000, "
            "top lag partitions=[orders-0:12345]; offset position snapshot: "
            "current committed offsets and log end offsets captured"
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.required_evidence_satisfied is False


@pytest.mark.asyncio
async def test_consumer_lag_spike_accepts_live_metric_trend_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONSUMER_LAG_SPIKE"),
        _retrieval(
            "consumer lag p95 metric: 30 points, latest=12345.000, "
            "first=42.000, delta=12303.000; lag p95 증가",
            "offset progression commit rate metric: 30 points, latest=5.000 records/sec, "
            "first=120.000, delta=-115.000; offset progression 둔화 commit rate 감소",
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "CONSUMER_LAG_SPIKE"
    assert top.required_evidence_satisfied is True
    assert top.confidence >= 0.80


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
async def test_connector_status_and_trace_commit_without_logs_or_metrics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONNECTOR_TASK_FAILED"),
        _retrieval_items(
            (
                "커넥터 orders-source 상태 FAILED. task trace 확인됨. search_logs=0, get_metrics=0",
                EvidenceType.TOOL_RESULT,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "CONNECTOR_TASK_FAILED"
    assert top.confidence >= 0.60


@pytest.mark.asyncio
async def test_required_causal_evidence_without_temporality_is_demoted_to_supporting(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONFIG_CHANGE_REGRESSION"),
        _retrieval(
            "config 변경 시점 error latency 증가 change time correlation 없음; config 변경과 error가 같은 창에서 관측됨",
            "변경 diff와 증상 계층이 연결됨 connector task config diff",
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "RECENT_CONFIG_CHANGE_REGRESSION"
    assert top.required_evidence_satisfied is False
    assert "config 변경 시점 이후 error/latency 증가" in top.evidence_gap
    assert "ev-1" in top.supporting_evidence_ids
    assert "step 1 correlational" in top.explanation


@pytest.mark.asyncio
async def test_temporal_required_evidence_outputs_causal_chain(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONFIG_CHANGE_REGRESSION"),
        _retrieval(
            "config 변경 시점 이후 error latency 증가 change time correlation: 배포 직후 오류 증가",
            "변경 diff와 증상 계층이 연결됨 connector task config diff",
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "RECENT_CONFIG_CHANGE_REGRESSION"
    assert top.required_evidence_satisfied is True
    assert "causal chain" in top.explanation
    assert "step 1 causal" in top.explanation
    assert "step 2 causal" in top.explanation


@pytest.mark.asyncio
async def test_source_auth_trace_commits_with_partial_observability(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("SOURCE_AUTH_FAILURE"),
        _retrieval_items(
            (
                "connector task trace: source 토큰 만료로 인증 실패. search_logs=0, get_metrics=0",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "SOURCE_AUTH_EXPIRED"
    assert top.confidence >= 0.60


@pytest.mark.asyncio
async def test_normal_operational_evidence_does_not_commit_fault(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval("정상 범위 메트릭. connector status RUNNING. source endpoint reachable."),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_running_connector_trace_mention_does_not_commit_task_failed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("CONNECTOR_TASK_FAILED"),
        _retrieval("connector status RUNNING. task trace 확인됨. no failed task."),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_normal_auth_evidence_does_not_commit_auth_expired(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("SOURCE_AUTH_FAILURE"),
        _retrieval("source auth status normal. auth 변경 없음. token valid. no auth error."),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_knowledge_evidence_does_not_satisfy_required_rules(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("SINK_WRITE_LATENCY"),
        _retrieval_items(
            (
                "[catalog] Evidence Matrix: sink write latency 증가 | Required | write p95 증가",
                EvidenceType.KNOWLEDGE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_user_request_snapshot_can_satisfy_schema_mismatch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    result = await run_rca(
        _classifier("SCHEMA_MISMATCH"),
        _retrieval_items(
            (
                "Evidence points to schema compatibility failure: serialization error and "
                "incompatible schema, recent subject version update, sample payload has "
                "field type mismatch. Sink is reachable and credentials valid.",
                EvidenceType.SNAPSHOT,
            ),
            (
                "[catalog] Evidence Matrix: sink write latency 증가 | Required | write p95 증가",
                EvidenceType.KNOWLEDGE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "SCHEMA_MISMATCH"
    assert top.required_evidence_satisfied is True
    assert top.confidence >= 0.60


@pytest.mark.asyncio
async def test_embedding_assist_can_bridge_required_evidence_vocabulary(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    _patch_semantic_embedder(monkeypatch)

    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval_items(
            (
                "source connection timeout 증가 pipeline_source_connection_timeout_total 증가",
                EvidenceType.METRIC,
            ),
            (
                "upstream source dependency stopped responding",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "SOURCE_DB_CONNECTION_TIMEOUT"
    assert top.required_evidence_satisfied is True
    assert top.confidence >= 0.60


@pytest.mark.asyncio
async def test_embedding_assist_does_not_bridge_required_from_supporting_anchor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    _patch_semantic_embedder(monkeypatch)

    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval_items(
            (
                "sink write 단계 정상, no sink-side latency regression observed",
                EvidenceType.SNAPSHOT,
            ),
            (
                "upstream source dependency stopped responding",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.required_evidence_satisfied is False
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_embedding_assist_does_not_commit_without_lexical_anchor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    _patch_semantic_embedder(monkeypatch)

    result = await run_rca(
        _classifier("SOURCE_AUTH_FAILURE"),
        _retrieval_items(
            (
                "upstream database login was rejected because the stored secret is stale",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.required_evidence_satisfied is False
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_embedding_assist_can_be_disabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    _patch_semantic_embedder(monkeypatch, enabled=False)

    result = await run_rca(
        _classifier("SOURCE_AUTH_FAILURE"),
        _retrieval_items(
            (
                "upstream database login was rejected because the stored secret is stale",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_embedding_assist_respects_similarity_threshold(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    _patch_semantic_embedder(monkeypatch)
    monkeypatch.setattr(settings, "rca_embedding_match_threshold", 1.01)

    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval_items(
            (
                "source connection timeout 증가 pipeline_source_connection_timeout_total 증가",
                EvidenceType.METRIC,
            ),
            (
                "upstream source dependency stopped responding",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "SOURCE_DB_CONNECTION_TIMEOUT"
    assert top.required_evidence_satisfied is False
    assert top.confidence < 0.80
    assert top.evidence_gap


@pytest.mark.parametrize("mode", ["none", "short", "non_vector", "dimension_mismatch"])
@pytest.mark.asyncio
async def test_embedding_assist_falls_back_when_embedder_returns_malformed(
    monkeypatch: pytest.MonkeyPatch,
    mode: str,
) -> None:
    _patch_llm(monkeypatch)
    monkeypatch.setattr(settings, "rca_embedding_match_enabled", True)
    monkeypatch.setattr(settings, "rca_embedding_match_threshold", 0.8)
    monkeypatch.setattr(settings, "rca_embedding_match_prefer_openai", False)
    monkeypatch.setattr("app.knowledge.embedder.get_embedder", lambda **_: _MalformedEmbedder(mode))

    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval_items(
            (
                "sink write 단계 정상, no sink-side latency regression observed",
                EvidenceType.SNAPSHOT,
            ),
            (
                "upstream source dependency stopped responding",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.required_evidence_satisfied is False
    assert top.confidence < 0.60


@pytest.mark.asyncio
async def test_embedding_assist_falls_back_when_embedder_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    monkeypatch.setattr(settings, "rca_embedding_match_enabled", True)
    monkeypatch.setattr(settings, "rca_embedding_match_threshold", 0.8)

    def raise_embedder(**_: object) -> object:
        raise RuntimeError("embedding provider unavailable")

    monkeypatch.setattr("app.knowledge.embedder.get_embedder", raise_embedder)

    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval_items(
            (
                "sink write 단계 정상, no sink-side latency regression observed",
                EvidenceType.SNAPSHOT,
            ),
            (
                "upstream source dependency stopped responding",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
    assert top.required_evidence_satisfied is False
    assert top.confidence < 0.60


def test_structured_required_rules_opt_out_of_semantic_matching() -> None:
    structured_rules = {
        ("CONNECTOR_TASK_FAILED", "connector task status `FAILED`"),
        ("CONSUMER_LAG_SPIKE", "consumer lag 급증"),
        ("CONSUMER_LAG_SPIKE", "offset progression 둔화"),
        ("POD_OOM_KILLED", "pod last state OOMKilled"),
        ("DEPLOYMENT_REGRESSION", "배포 이후 error/latency 증가"),
        ("RECENT_SCHEMA_CHANGE_REGRESSION", "schema version 변경 이후 schema/serialization error 증가"),
        ("PIPELINE_FRESHNESS_DELAY", "pipeline stage 중 병목 단계 식별"),
    }

    for root_cause_id, evidence in structured_rules:
        profile = get_evidence_profile(root_cause_id)
        assert profile is not None
        rule = next(rule for rule in profile.required if rule.evidence == evidence)
        assert rule.semantic_allowed is False


@pytest.mark.asyncio
async def test_structured_semantic_opt_out_blocks_embedding_only_required_match(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    _patch_semantic_embedder(monkeypatch)

    result = await run_rca(
        _classifier("POD_OOM_KILLED"),
        _retrieval_items(
            (
                "restart count 증가 restart count delta",
                EvidenceType.METRIC,
            ),
            (
                "container exceeded memory limit and was killed by Kubernetes",
                EvidenceType.TRACE,
            ),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "POD_OOM_KILLED"
    assert top.required_evidence_satisfied is False
    assert "pod last state OOMKilled" in top.evidence_gap
    assert top.confidence < 0.80


@pytest.mark.asyncio
async def test_negative_evidence_voids_embedding_only_positive_matches(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_llm(monkeypatch)
    _patch_semantic_embedder(monkeypatch)

    result = await run_rca(
        _classifier("SOURCE_CONNECTION_TIMEOUT"),
        _retrieval_items(
            ("alpha condition observed", EvidenceType.TRACE),
            ("beta condition observed", EvidenceType.TRACE),
            ("gamma condition observed", EvidenceType.METRIC),
            ("delta condition observed", EvidenceType.METRIC),
        ),
    )

    top = result.root_cause_candidates[0]
    assert top.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"
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
