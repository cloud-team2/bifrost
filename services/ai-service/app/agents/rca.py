"""RCA assistant backed by root-cause and evidence catalogs."""
from __future__ import annotations

import json
import logging
import math
import re
from collections.abc import Sequence
from dataclasses import dataclass, replace
from typing import Any

from app.catalogs.evidence_matrix import get_evidence_profile
from app.catalogs.incident_rootcause_map import get_root_cause_candidates
from app.catalogs.root_causes import get_root_cause, list_root_causes
from app.catalogs.types import EvidenceProfile, EvidenceRule, RootCause
from app.core.config import settings
from app.evidence.metadata import strip_control_metadata
from app.prompts.rca import SYSTEM_PROMPT, build_user_prompt
from app.schemas.outputs import ClassifierOutput, RcaOutput, RetrievalOutput
from app.schemas.state import EvidenceItem, EvidenceType, RootCauseCandidate

UNKNOWN_ROOT_CAUSE_ID = "UNKNOWN_WITH_EVIDENCE_GAP"
UNKNOWN_INCIDENT_TYPE = "UNKNOWN_NEEDS_MORE_EVIDENCE"
MIN_CONFIDENT_ROOT_CAUSE = 0.60
LLM_TIE_MARGIN = 0.10
MAX_CANDIDATES = 5

# #962 근접 증상(task 가 FAILED 다)일 뿐 "왜 실패했는지"가 더 구체적 원인으로 입증되면 그 아래로 강등할 root cause.
_SYMPTOM_ROOT_CAUSES = frozenset({"CONNECTOR_TASK_FAILED", "PIPELINE_TASK_RETRY_EXHAUSTED"})
_CAUSAL_DEPTH_MARGIN = 0.03
_DOMINANT_CAUSES: dict[str, frozenset[str]] = {
    "CREDENTIAL_ROTATION_REGRESSION": frozenset({"SOURCE_AUTH_EXPIRED", "SINK_AUTH_EXPIRED"}),
}

logger = logging.getLogger(__name__)

_TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣]+")
_CONNECTOR_TASK_FAILED_ID = "CONNECTOR_TASK_FAILED"
_TOKEN_ALIASES = {
    "상태": "status",
    "커넥터": "connector",
    "오류": "error",
    "에러": "error",
    "인증": "auth",
    "권한": "permission",
    "토큰": "token",
    "만료": "expired",
    "연결": "connection",
    "네트워크": "network",
    "도달": "reachability",
    "호스트": "host",
    "스키마": "schema",
    "불일치": "mismatch",
    "역직렬화": "deserialization",
    "직후": "after",
    "이후": "after",
    "후": "after",
    "배포": "deployment",
    "이미지": "image",
    "업데이트": "update",
    "중복": "duplicate",
    "레코드": "record",
    "소진": "exhausted",
}
_GENERIC_TOKENS = {
    "and",
    "or",
    "the",
    "with",
    "error",
    "failure",
    "failed",
    "log",
    "metric",
    "evidence",
    "summary",
    "pipeline",
    "task",
    "connector",
    "consumer",
    "source",
    "sink",
    "kafka",
    "증가",
    "급증",
    "감소",
    "변경",
    "정상",
    "지연",
    "존재",
    "또는",
    "단계",
}


@dataclass(frozen=True, slots=True)
class _RuleMatch:
    rule: EvidenceRule
    evidence_ids: set[str]
    semantic_evidence_ids: set[str]
    demoted_evidence_ids: set[str] | None = None

    @property
    def lexical_evidence_ids(self) -> set[str]:
        return self.evidence_ids - self.semantic_evidence_ids

    @property
    def supporting_evidence_ids(self) -> set[str]:
        return set(self.demoted_evidence_ids or set())


@dataclass(frozen=True, slots=True)
class _EvaluatedCandidate:
    root_cause: RootCause
    profile: EvidenceProfile
    confidence: float
    required_evidence_satisfied: bool
    supporting_evidence_ids: set[str]
    negative_evidence_ids: set[str]
    evidence_gap: list[str]
    explanation: str

    @property
    def root_cause_id(self) -> str:
        return self.root_cause.root_cause_id


@dataclass(frozen=True, slots=True)
class _RuleEvidenceMatch:
    lexical: bool = False
    semantic: bool = False

    @property
    def matched(self) -> bool:
        return self.lexical or self.semantic


class _SemanticEvidenceMatcher:
    def __init__(self, *, enabled: bool, threshold: float, vectors: dict[str, list[float]] | None = None) -> None:
        self.enabled = enabled
        self.threshold = threshold
        self._vectors = vectors or {}

    @classmethod
    async def build(
        cls,
        candidate_ids: list[str],
        evidence_items: list[EvidenceItem],
    ) -> "_SemanticEvidenceMatcher":
        if not settings.rca_embedding_match_enabled:
            return cls(enabled=False, threshold=settings.rca_embedding_match_threshold)

        texts = _semantic_texts(candidate_ids, evidence_items)
        if len(texts) < 2:
            return cls(enabled=False, threshold=settings.rca_embedding_match_threshold)

        try:
            from app.knowledge.embedder import get_embedder

            embedder = get_embedder(prefer_openai=settings.rca_embedding_match_prefer_openai)
            embeddings = await embedder.embed_texts(texts)
            vectors = _validated_embedding_vectors(texts, embeddings)
        except Exception as exc:
            logger.warning("RCA semantic evidence matcher disabled after embedder failure: %s", exc)
            return cls(enabled=False, threshold=settings.rca_embedding_match_threshold)

        return cls(
            enabled=True,
            threshold=settings.rca_embedding_match_threshold,
            vectors=vectors,
        )

    def matches(self, rule: EvidenceRule, item: EvidenceItem) -> bool:
        if not self.enabled:
            return False

        evidence_vector = self._vectors.get(_sanitize_summary(item.summary))
        if not evidence_vector:
            return False

        for phrase in _semantic_rule_phrases(rule):
            phrase_vector = self._vectors.get(phrase)
            if phrase_vector and _cosine_similarity(phrase_vector, evidence_vector) >= self.threshold:
                return True
        return False


async def run_rca(classifier_out: ClassifierOutput | None, retrieval_out: RetrievalOutput | None) -> RcaOutput:
    evidence_items = [
        item
        for item in (retrieval_out.evidence_items if retrieval_out else [])
        if _is_observed_evidence(item)
    ]
    candidate_ids = _candidate_pool(classifier_out)

    if not evidence_items:
        return _unknown_output(["evidence_items_empty"])
    if candidate_ids == [UNKNOWN_ROOT_CAUSE_ID]:
        return _unknown_output(["classifier_unknown_needs_more_evidence"])
    if not candidate_ids:
        return _unknown_output(["classifier_incident_type_mapping_missing"])

    matcher = await _SemanticEvidenceMatcher.build(candidate_ids, evidence_items)
    incident_types = _classifier_types(classifier_out)
    candidates = [
        candidate
        for candidate_id in candidate_ids
        if (candidate := await _evaluate_candidate(candidate_id, evidence_items, matcher, incident_types)) is not None
    ]
    candidates.sort(key=lambda item: item.confidence, reverse=True)

    if _needs_llm_assist(candidates):
        llm_selection = await _run_llm_assist(candidates, evidence_items, _classifier_types(classifier_out))
        if llm_selection:
            candidates = _apply_llm_selection(candidates, llm_selection)

    # #962 근접 증상(task FAILED)보다 입증된 심층 원인을 우선 — 마지막에 적용해 LLM 선택도 덮어쓴다.
    candidates = _demote_symptom_below_confirmed_root_cause(candidates)
    candidates = _demote_direct_effect_below_confirmed_regression(candidates)
    candidates.sort(key=lambda item: item.confidence, reverse=True)
    if not candidates or candidates[0].confidence < MIN_CONFIDENT_ROOT_CAUSE:
        return _unknown_output(_collect_missing_required(candidates) or ["required_evidence_missing"])

    return RcaOutput(
        root_cause_candidates=[
            _to_schema(candidate)
            for candidate in candidates[:MAX_CANDIDATES]
            if candidate.confidence > 0
        ]
    )


def _candidate_pool(classifier_out: ClassifierOutput | None) -> list[str]:
    incident_types = _classifier_types(classifier_out)
    if not incident_types:
        return []
    if all(item == UNKNOWN_INCIDENT_TYPE for item in incident_types):
        return [UNKNOWN_ROOT_CAUSE_ID]

    candidate_ids: list[str] = []
    seen: set[str] = set()
    for incident_type in incident_types:
        for root_cause_id in get_root_cause_candidates(incident_type):
            root_cause = get_root_cause(root_cause_id)
            if root_cause and root_cause_id not in seen:
                seen.add(root_cause_id)
                candidate_ids.append(root_cause_id)
    return candidate_ids


def _classifier_types(classifier_out: ClassifierOutput | None) -> list[str]:
    if classifier_out is None:
        return []
    return [item.type for item in classifier_out.classification.incident_types]


async def _evaluate_candidate(
    root_cause_id: str,
    evidence_items: list[EvidenceItem],
    matcher: _SemanticEvidenceMatcher,
    incident_types: list[str],
) -> _EvaluatedCandidate | None:
    root_cause = get_root_cause(root_cause_id)
    profile = get_evidence_profile(root_cause_id)
    if root_cause is None or profile is None:
        return None

    required_matches = _match_rules(profile.required, evidence_items, matcher, incident_types)
    supporting_matches = _match_rules(profile.supporting, evidence_items, matcher, incident_types)
    negative_matches = _match_rules(profile.negative, evidence_items, matcher, incident_types)
    negative_ids = _matched_evidence_ids(negative_matches)
    has_required_lexical_anchor = _has_lexical_required_anchor(required_matches)
    has_positive_lexical_anchor = has_required_lexical_anchor or _has_lexical_anchor(supporting_matches)
    if negative_ids:
        required_matches = _discard_semantic_only_matches(required_matches)
        supporting_matches = _discard_semantic_only_matches(supporting_matches)
    else:
        if not has_required_lexical_anchor:
            required_matches = _discard_semantic_only_matches(required_matches)
        if not has_positive_lexical_anchor:
            supporting_matches = _discard_semantic_only_matches(supporting_matches)

    evidence_by_id = {item.evidence_id: item for item in evidence_items}
    required_matches = _demote_non_temporal_required_matches(required_matches, evidence_by_id)
    matched_required = [item for item in required_matches if item.evidence_ids]
    evidence_gap = [item.rule.evidence for item in required_matches if not item.evidence_ids]

    required_satisfied = not evidence_gap
    supporting_ids = _matched_evidence_ids(supporting_matches) | _demoted_evidence_ids(required_matches)
    confidence = _score_confidence(
        root_cause=root_cause,
        required_matched=len(matched_required),
        required_total=len(profile.required),
        supporting_matched=sum(1 for item in supporting_matches if item.evidence_ids),
        supporting_total=len(profile.supporting),
        negative_count=len(negative_ids),
    )
    if _connector_failed_status_missing(root_cause_id, required_matches):
        confidence = min(confidence, 0.59)

    return _EvaluatedCandidate(
        root_cause=root_cause,
        profile=profile,
        confidence=confidence,
        required_evidence_satisfied=required_satisfied,
        supporting_evidence_ids=supporting_ids,
        negative_evidence_ids=negative_ids,
        evidence_gap=evidence_gap,
        explanation=_build_explanation(
            root_cause,
            required_satisfied,
            len(matched_required),
            len(profile.required),
            supporting_ids,
            negative_ids,
            _causal_chain_explanation(required_matches, supporting_matches),
        ),
    )


def _match_rules(
    rules: tuple[EvidenceRule, ...],
    evidence_items: list[EvidenceItem],
    matcher: _SemanticEvidenceMatcher,
    incident_types: list[str],
) -> list[_RuleMatch]:
    matches: list[_RuleMatch] = []
    for rule in rules:
        evidence_ids: set[str] = set()
        semantic_evidence_ids: set[str] = set()
        for item in evidence_items:
            match = _rule_evidence_match(rule, item, matcher, incident_types)
            if match.matched:
                evidence_ids.add(item.evidence_id)
            if match.semantic and not match.lexical:
                semantic_evidence_ids.add(item.evidence_id)
        matches.append(
            _RuleMatch(
                rule=rule,
                evidence_ids=evidence_ids,
                semantic_evidence_ids=semantic_evidence_ids,
            )
        )
    return matches


def _rule_evidence_match(
    rule: EvidenceRule,
    item: EvidenceItem,
    matcher: _SemanticEvidenceMatcher,
    incident_types: list[str],
) -> _RuleEvidenceMatch:
    lexical = _rule_matches_evidence(rule, item, incident_types)
    semantic = False
    if not lexical and _allows_semantic_rule_match(rule, item):
        semantic = matcher.matches(rule, item)
    return _RuleEvidenceMatch(lexical=lexical, semantic=semantic)


def _discard_semantic_only_matches(matches: list[_RuleMatch]) -> list[_RuleMatch]:
    return [
        _RuleMatch(
            rule=match.rule,
            evidence_ids=match.lexical_evidence_ids,
            semantic_evidence_ids=set(),
            demoted_evidence_ids=match.demoted_evidence_ids,
        )
        for match in matches
    ]


def _demote_non_temporal_required_matches(
    matches: list[_RuleMatch],
    evidence_by_id: dict[str, EvidenceItem],
) -> list[_RuleMatch]:
    gated: list[_RuleMatch] = []
    for match in matches:
        if not match.rule.temporality_required:
            gated.append(match)
            continue
        temporal_ids = {
            evidence_id
            for evidence_id in match.evidence_ids
            if _has_temporal_precedence(evidence_by_id[evidence_id])
        }
        gated.append(
            _RuleMatch(
                rule=match.rule,
                evidence_ids=temporal_ids,
                semantic_evidence_ids=match.semantic_evidence_ids & temporal_ids,
                demoted_evidence_ids=match.evidence_ids - temporal_ids,
            )
        )
    return gated


def _demoted_evidence_ids(matches: list[_RuleMatch]) -> set[str]:
    evidence_ids: set[str] = set()
    for match in matches:
        evidence_ids.update(match.supporting_evidence_ids)
    return evidence_ids


def _has_lexical_required_anchor(required_matches: list[_RuleMatch]) -> bool:
    return _has_lexical_anchor(required_matches)


def _has_lexical_anchor(matches: list[_RuleMatch]) -> bool:
    return any(match.lexical_evidence_ids for match in matches)


def _allows_semantic_rule_match(rule: EvidenceRule, item: EvidenceItem) -> bool:
    if not settings.rca_embedding_match_enabled:
        return False
    if not _sanitize_summary(item.summary):
        return False
    return _allows_semantic_rule(rule)


def _allows_semantic_rule(rule: EvidenceRule) -> bool:
    if not rule.semantic_allowed:
        return False
    return any(_semantic_phrase_allowed(phrase) for phrase in _semantic_rule_phrases(rule))


def _validated_embedding_vectors(texts: list[str], embeddings: object) -> dict[str, list[float]]:
    if not isinstance(embeddings, Sequence) or isinstance(embeddings, (str, bytes)):
        raise ValueError("embedder returned a non-sequence response")
    if len(embeddings) != len(texts):
        raise ValueError("embedder returned a different number of vectors than inputs")

    vectors: dict[str, list[float]] = {}
    expected_dimensions: int | None = None
    for text, vector in zip(texts, embeddings, strict=True):
        if not isinstance(vector, Sequence) or isinstance(vector, (str, bytes)) or not vector:
            raise ValueError("embedder returned a non-vector item")
        try:
            normalized = [float(value) for value in vector]
        except (TypeError, ValueError) as exc:
            raise ValueError("embedder returned a non-numeric vector item") from exc
        if expected_dimensions is None:
            expected_dimensions = len(normalized)
        elif len(normalized) != expected_dimensions:
            raise ValueError("embedder returned vectors with inconsistent dimensions")
        vectors[text] = normalized
    return vectors


def _semantic_texts(candidate_ids: list[str], evidence_items: list[EvidenceItem]) -> list[str]:
    texts: list[str] = []

    def add(value: str) -> None:
        value = value.strip()
        if value and value not in texts:
            texts.append(value)

    for item in evidence_items:
        add(_sanitize_summary(item.summary))

    for candidate_id in candidate_ids:
        profile = get_evidence_profile(candidate_id)
        if profile is None:
            continue
        for rule in (*profile.required, *profile.supporting, *profile.negative):
            if _allows_semantic_rule(rule):
                for phrase in _semantic_rule_phrases(rule):
                    if _semantic_phrase_allowed(phrase):
                        add(phrase)
    return texts


def _semantic_rule_phrases(rule: EvidenceRule) -> list[str]:
    phrases: list[str] = []
    for value in (rule.evidence, rule.example or ""):
        value = value.strip()
        if value and value not in phrases:
            phrases.append(value)
        for alt in _split_alternatives(value):
            if alt and alt not in phrases:
                phrases.append(alt)
    return phrases


def _semantic_phrase_allowed(phrase: str) -> bool:
    tokens = _meaningful_tokens(phrase)
    if len(tokens) >= 2:
        return True
    compact = "".join(_tokens(phrase))
    return len(tokens) == 1 and len(compact) >= 12


def _cosine_similarity(left: Sequence[float], right: Sequence[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(a * a for a in left))
    right_norm = math.sqrt(sum(b * b for b in right))
    if left_norm == 0.0 or right_norm == 0.0:
        return 0.0
    return dot / (left_norm * right_norm)


def _rule_matches_evidence(rule: EvidenceRule, item: EvidenceItem, incident_types: list[str]) -> bool:
    summary = _sanitize_summary(item.summary)
    evidence_text = f"{item.type} {summary}".casefold()
    if _connector_failed_status_rule(rule):
        return _has_connector_failed_status(summary)
    if _connector_trace_rule(rule) and _negates_connector_trace(summary):
        return False
    if _auth_rule(rule):
        return _has_scoped_auth_evidence(rule, summary, incident_types)
    if _consumer_lag_trend_rule(rule):
        return _has_consumer_lag_trend_evidence(rule, summary)
    if _source_network_sink_negative_rule(rule) and not _has_sink_context(summary):
        return False
    if _negates_rule_fault(rule, summary):
        return False

    rule_text = rule.evidence.casefold()
    example_text = (rule.example or "").casefold()
    if rule_text and rule_text in evidence_text:
        return True
    if example_text and example_text in evidence_text:
        return True

    evidence_tokens = set(_tokens(evidence_text))
    rule_tokens = _meaningful_tokens(rule.evidence)
    example_tokens = _meaningful_tokens(rule.example or "")
    if _tokens_match(rule_tokens, evidence_tokens) or _tokens_match(example_tokens, evidence_tokens):
        return True

    # #767: example/evidence may list several alternative phrasings separated by
    # commas (English raw-log tokens AND Korean operations summaries). Match if any
    # single alternative matches, so Korean summaries are not missed.
    for alt in _split_alternatives(rule.evidence) + _split_alternatives(rule.example or ""):
        alt_cf = alt.casefold()
        if alt_cf and alt_cf in evidence_text:
            return True
        if _tokens_match(_meaningful_tokens(alt), evidence_tokens):
            return True
    return False


def _has_temporal_precedence(item: EvidenceItem) -> bool:
    summary = _sanitize_summary(item.summary)
    normalized = _normalize_text(summary)
    return bool(
        re.search(
            r"\b(?:after|since|following|followed|before|prior|preced(?:e|ed|ing)|then|subsequent)\b",
            normalized,
        )
        or re.search(r"(?:이후|직후|후|뒤이어|이어서|다음|전후|전에|먼저|선행)", summary)
    )


def _split_alternatives(value: str) -> list[str]:
    parts = re.split(r"[,，]|\s+(?:또는|or)\s+", value, flags=re.IGNORECASE)
    return [part.strip() for part in parts if part.strip()]


def _connector_failed_status_rule(rule: EvidenceRule) -> bool:
    return (
        rule.root_cause_id == _CONNECTOR_TASK_FAILED_ID
        and "status" in rule.evidence.casefold()
        and "failed" in rule.evidence.casefold()
    )


def _connector_trace_rule(rule: EvidenceRule) -> bool:
    return (
        rule.root_cause_id == _CONNECTOR_TASK_FAILED_ID
        and ("trace" in rule.evidence.casefold() or "worker log" in rule.evidence.casefold())
    )


def _consumer_lag_trend_rule(rule: EvidenceRule) -> bool:
    return rule.root_cause_id == "CONSUMER_LAG_SPIKE" and rule.kind == "required"


def _auth_rule(rule: EvidenceRule) -> bool:
    return rule.root_cause_id in {"SOURCE_AUTH_EXPIRED", "SINK_AUTH_EXPIRED"} and rule.kind == "required"


def _source_network_sink_negative_rule(rule: EvidenceRule) -> bool:
    return (
        rule.root_cause_id == "SOURCE_NETWORK_REACHABILITY"
        and rule.kind == "negative"
        and "sink dependency" in rule.evidence.casefold()
    )


def _has_sink_context(summary: str) -> bool:
    normalized = _normalize_text(summary)
    return bool(
        re.search(r"\b(?:sink|write|jdbc|flush|batch)\b", normalized)
        or re.search(r"(?:싱크|쓰기)", summary)
    )


def _has_scoped_auth_evidence(rule: EvidenceRule, summary: str, incident_types: list[str]) -> bool:
    normalized = _normalize_text(summary)
    if _has_auth_negation(summary) or _has_auth_negation(normalized):
        return False
    has_auth_signal = bool(
        re.search(
            r"\b(?:auth|authentication|permission|credential|token|password|sasl|expired|denied)\b",
            normalized,
        )
        or re.search(r"(?:인증\s*실패|권한\s*거부|토큰\s*만료|credential\s*만료)", summary, re.IGNORECASE)
    )
    if not has_auth_signal:
        return False
    has_source_hint = bool(re.search(r"\b(?:source|extract|read)\b", normalized) or re.search(r"(?:소스|읽기)", summary))
    has_sink_hint = bool(re.search(r"\b(?:sink|write|jdbc|flush|batch)\b", normalized) or re.search(r"(?:쓰기|싱크)", summary))
    auth_source_scoped = _has_scoped_fault_hint(summary, "source", "auth")
    auth_sink_scoped = _has_scoped_fault_hint(summary, "sink", "auth")
    incident_set = set(incident_types)
    if rule.root_cause_id == "SOURCE_AUTH_EXPIRED":
        if auth_sink_scoped and not auth_source_scoped:
            return False
        return auth_source_scoped or (
            "SOURCE_AUTH_FAILURE" in incident_set and not auth_sink_scoped and not has_sink_hint
        )
    if auth_source_scoped and not auth_sink_scoped:
        return False
    return auth_sink_scoped or (
        "SINK_AUTH_FAILURE" in incident_set and not auth_source_scoped and not has_source_hint
    )


def _has_scoped_fault_hint(summary: str, scope: str, fault: str) -> bool:
    scope_patterns = {
        "source": r"(?:source|extract|read|소스|읽기)",
        "sink": r"(?:sink|write|jdbc|flush|batch|싱크|쓰기)",
    }
    fault_patterns = {
        "auth": (
            r"(?:auth|authentication|permission|credential|token|password|sasl|"
            r"expired|denied|인증|권한|토큰|credential)"
        ),
    }
    scope_re = scope_patterns[scope]
    fault_re = fault_patterns[fault]
    return bool(
        re.search(rf"{scope_re}.{{0,80}}{fault_re}", summary, re.IGNORECASE)
        or re.search(rf"{fault_re}.{{0,80}}{scope_re}", summary, re.IGNORECASE)
    )


def _has_auth_negation(normalized: str) -> bool:
    return bool(
        re.search(r"\b(?:no|not|without)\s+(?:\w+\s+){0,3}(?:auth|authentication|permission|credential|token)\b", normalized)
        or re.search(r"\b(?:auth|authentication|permission|credential|token)\s+(?:status\s+)?(?:normal|valid|healthy)\b", normalized)
        or re.search(
            r"\b(?:auth|authentication|permission|credential|token)\s+"
            r"(?:error|failure|issue|problem|문제)?\s*(?:없음|아님|아닌|없다|정상|유효)\b",
            normalized,
        )
        or re.search(r"(?:인증|권한|토큰|credential)\s*(?:오류|실패|문제)?\s*(?:없음|아님|아닌|없다|정상|유효)", normalized)
    )


def _negates_rule_fault(rule: EvidenceRule, summary: str) -> bool:
    normalized = _normalize_text(summary)
    rule_text = f"{rule.evidence} {rule.example or ''}".casefold()
    if "schema" in rule_text or "serialization" in rule_text or "deserialization" in rule_text:
        return bool(
            re.search(r"\bno\s+(?:schema|serialization|deserialization)\s+(?:error|failure)\b", normalized)
            or re.search(r"\bschema\s+(?:status\s+)?(?:normal|valid|compatible|unchanged|healthy)\b", normalized)
            or re.search(r"(?:schema|스키마).*(?:정상|호환|변경\s*없음|오류\s*없음)", normalized)
        )
    if "config" in rule_text:
        return bool(
            re.search(r"\bno\s+config\s+(?:change|error|diff|validation)\b", normalized)
            or re.search(r"\bconfig\s+(?:status\s+)?(?:normal|valid|unchanged|snapshot)\b", normalized)
            or re.search(r"(?:config|설정).*(?:정상|변경\s*없음|오류\s*없음|스냅샷)", normalized)
        )
    if "timeout" in rule_text or "connection" in rule_text or "reachability" in rule_text:
        return bool(
            re.search(r"\b(?:no|without)\s+(?:\w+\s+){0,3}(?:timeout|network|reachability|connection)\s+(?:evidence|error|failure|issue)\b", normalized)
            or re.search(r"\b(?:endpoint|connection|network)\s+(?:reachable|healthy|normal|ok)\b", normalized)
            or re.search(r"(?:연결|네트워크|endpoint).*(?:정상|성공|가능)", normalized)
        )
    if "constraint" in rule_text or "duplicate" in rule_text:
        return bool(
            re.search(r"\bno\s+(?:duplicate|constraint)\s+(?:error|violation|records?)\b", normalized)
            or re.search(r"(?:중복|제약).*(?:없음|정상)", normalized)
        )
    return False


def _has_consumer_lag_trend_evidence(rule: EvidenceRule, summary: str) -> bool:
    normalized = _normalize_text(summary)
    if "consumer lag" in rule.evidence.casefold():
        has_lag_signal = "consumer lag" in normalized or "lag p95" in normalized
        has_increase_signal = bool(
            re.search(r"\b(?:spike|increas(?:e|ed|ing)|surge|jump)\b", normalized)
            or re.search(r"(?:급증|증가|상승|치솟)", normalized)
        )
        return has_lag_signal and has_increase_signal

    if "offset progression" in rule.evidence.casefold():
        has_offset_signal = (
            "offset progression" in normalized
            or "commit rate" in normalized
            or "committed offsets" in normalized
        )
        has_slowing_signal = bool(
            re.search(
                r"\b(?:slow(?:ed|ing)?|decreas(?:e|ed|ing)|"
                r"drop(?:ped|ping)?|declin(?:e|ed|ing))\b",
                normalized,
            )
            or re.search(r"(?:둔화|감소|하락|저하)", normalized)
        )
        return has_offset_signal and has_slowing_signal

    return False


def _connector_failed_status_missing(
    root_cause_id: str,
    required_matches: list[_RuleMatch],
) -> bool:
    if root_cause_id != _CONNECTOR_TASK_FAILED_ID:
        return False
    return any(
        _connector_failed_status_rule(match.rule) and not match.evidence_ids
        for match in required_matches
    )


def _has_connector_failed_status(summary: str) -> bool:
    normalized = summary.casefold()
    if re.search(r"(?:status|상태)\s*[:=]?\s*(?:running|ok|healthy|정상)", normalized):
        return False
    if re.search(r"\b(?:no|not|without)\s+(?:\w+\s+){0,3}(?:failed|failure)\b", normalized):
        return False
    if re.search(r"(?:실패|오류)\s*(?:없음|아님|아닌|없다)", normalized):
        return False
    return bool(
        re.search(
            r"(?:status|상태)\s*[:=]?\s*(?:failed|failure|error|실패|오류)"
            r"|(?:task|태스크)\s+(?:status|상태)?\s*[:=]?\s*(?:failed|failure|error|실패|오류)",
            normalized,
        )
    )


def _negates_connector_trace(summary: str) -> bool:
    normalized = summary.casefold()
    return bool(
        re.search(r"\bno\s+(?:task\s+)?trace\b", normalized)
        or re.search(r"\btrace\s+(?:not found|missing|unavailable)\b", normalized)
        or re.search(r"(?:trace|worker log|트레이스|로그)\s*(?:없음|미확인|누락)", normalized)
    )


def _tokens_match(rule_tokens: set[str], evidence_tokens: set[str]) -> bool:
    if not rule_tokens:
        return False
    overlap = len(rule_tokens & evidence_tokens)
    required = len(rule_tokens) if len(rule_tokens) <= 2 else math.ceil(len(rule_tokens) * 0.5)
    return overlap >= required


def _tokens(value: str) -> list[str]:
    return [_TOKEN_ALIASES.get(token.casefold(), token.casefold()) for token in _TOKEN_RE.findall(value)]


def _normalize_text(value: str) -> str:
    return " ".join(_tokens(value))


def _sanitize_summary(value: str) -> str:
    return strip_control_metadata(value).strip()


def _meaningful_tokens(value: str) -> set[str]:
    return {token for token in _tokens(value) if len(token) > 1 and token not in _GENERIC_TOKENS}


def _matched_evidence_ids(matches: list[_RuleMatch]) -> set[str]:
    evidence_ids: set[str] = set()
    for match in matches:
        evidence_ids.update(match.evidence_ids)
    return evidence_ids


def _is_observed_evidence(item: EvidenceItem) -> bool:
    return item.type != EvidenceType.KNOWLEDGE and item.type != EvidenceType.KNOWLEDGE.value


def _score_confidence(
    *,
    root_cause: RootCause,
    required_matched: int,
    required_total: int,
    supporting_matched: int,
    supporting_total: int,
    negative_count: int,
) -> float:
    required_ratio = required_matched / required_total if required_total else 0.0
    supporting_ratio = supporting_matched / supporting_total if supporting_total else 0.0

    if required_total and required_matched == required_total:
        confidence = 0.88 + (supporting_ratio * 0.04)
        confidence = min(confidence, root_cause.default_confidence_cap)
    elif required_matched > 0:
        confidence = 0.72 + (required_ratio * 0.14) + (supporting_ratio * 0.04)
        confidence = min(confidence, root_cause.default_confidence_cap, 0.79)
    else:
        confidence = min(0.35 + (supporting_ratio * 0.10), root_cause.default_confidence_cap, 0.59)

    confidence -= 0.10 * negative_count
    if required_total and required_matched < required_total:
        confidence = min(confidence, root_cause.default_confidence_cap)
    return _clamp_confidence(confidence)


def _demote_symptom_below_confirmed_root_cause(
    candidates: list[_EvaluatedCandidate],
) -> list[_EvaluatedCandidate]:
    """#962 근접 증상(CONNECTOR_TASK_FAILED 등)은 "task 가 죽었다"는 사실일 뿐이다.
    같은 인시던트에서 더 구체적인 root cause(sink DB 연결 실패 등)가 required 증거까지 충족되면
    (=왜 죽었는지 입증됨) 그 심층 원인을 위로 올리도록, 증상 후보를 그 아래로 강등한다.
    심층 원인이 입증되지 않으면(예: 일반적 task 오류) 증상이 그대로 top 을 유지한다."""
    deeper_confidences = [
        candidate.confidence
        for candidate in candidates
        if candidate.root_cause_id not in _SYMPTOM_ROOT_CAUSES
        and candidate.required_evidence_satisfied
        and candidate.confidence >= MIN_CONFIDENT_ROOT_CAUSE
    ]
    if not deeper_confidences:
        return candidates
    ceiling = max(deeper_confidences) - _CAUSAL_DEPTH_MARGIN
    return [
        replace(candidate, confidence=_clamp_confidence(ceiling))
        if candidate.root_cause_id in _SYMPTOM_ROOT_CAUSES and candidate.confidence > ceiling
        else candidate
        for candidate in candidates
    ]


def _demote_direct_effect_below_confirmed_regression(
    candidates: list[_EvaluatedCandidate],
) -> list[_EvaluatedCandidate]:
    ceilings: dict[str, float] = {}
    for candidate in candidates:
        effects = _DOMINANT_CAUSES.get(candidate.root_cause_id)
        if not effects or not candidate.required_evidence_satisfied:
            continue
        ceiling = candidate.confidence - _CAUSAL_DEPTH_MARGIN
        for effect_id in effects:
            ceilings[effect_id] = max(ceilings.get(effect_id, 0.0), ceiling)
    if not ceilings:
        return candidates
    return [
        replace(candidate, confidence=_clamp_confidence(ceiling))
        if (ceiling := ceilings.get(candidate.root_cause_id)) is not None and candidate.confidence > ceiling
        else candidate
        for candidate in candidates
    ]


def _needs_llm_assist(candidates: list[_EvaluatedCandidate]) -> bool:
    if len(candidates) < 2:
        return False
    if candidates[0].confidence < MIN_CONFIDENT_ROOT_CAUSE:
        return False
    return candidates[0].confidence - candidates[1].confidence < LLM_TIE_MARGIN


async def _run_llm_assist(
    candidates: list[_EvaluatedCandidate],
    evidence_items: list[EvidenceItem],
    classifier_types: list[str],
) -> dict[str, Any] | None:
    try:
        from app.llm.provider import get_llm_provider
    except Exception:
        return None

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": build_user_prompt(
                candidate_pool=[(candidate.root_cause, candidate.profile) for candidate in candidates[:MAX_CANDIDATES]],
                evidence_summaries=[(item.evidence_id, _sanitize_summary(item.summary)) for item in evidence_items],
                classifier_types=classifier_types,
            ),
        },
    ]

    try:
        raw_response = await get_llm_provider().generate(messages, model=_rca_model_name())
    except Exception:
        return None

    parsed = _parse_json_object(raw_response)
    if not parsed:
        return None
    selected = parsed.get("selected_root_cause_id")
    candidate_ids = {candidate.root_cause_id for candidate in candidates}
    if selected not in candidate_ids:
        return None
    return parsed


def _apply_llm_selection(candidates: list[_EvaluatedCandidate], selection: dict[str, Any]) -> list[_EvaluatedCandidate]:
    selected_id = str(selection.get("selected_root_cause_id") or "")
    selected_confidence = _coerce_confidence(selection.get("confidence"), default=0.0)
    selected_explanation = selection.get("explanation")

    updated: list[_EvaluatedCandidate] = []
    for candidate in candidates:
        if candidate.root_cause_id != selected_id:
            updated.append(candidate)
            continue
        confidence = max(candidate.confidence, selected_confidence)
        confidence = min(confidence, _evidence_confidence_cap(candidate))
        explanation = candidate.explanation
        if isinstance(selected_explanation, str) and selected_explanation.strip():
            explanation = _sanitize_explanation(selected_explanation)
        updated.append(replace(candidate, confidence=_clamp_confidence(confidence), explanation=explanation))
    updated.sort(key=lambda item: item.confidence, reverse=True)
    return updated


def _evidence_confidence_cap(candidate: _EvaluatedCandidate) -> float:
    if candidate.required_evidence_satisfied:
        return 1.0
    if candidate.evidence_gap and candidate.confidence >= MIN_CONFIDENT_ROOT_CAUSE:
        return candidate.root_cause.default_confidence_cap
    return min(candidate.root_cause.default_confidence_cap, 0.59)


def _rca_model_name() -> str | None:
    try:
        from app.llm.model_router import model_for_agent
    except Exception:
        return None

    try:
        return model_for_agent("rca")
    except Exception:
        return None


def _parse_json_object(raw_response: str) -> dict[str, Any] | None:
    if not raw_response:
        return None
    try:
        value = json.loads(raw_response)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", raw_response, re.DOTALL)
        if not match:
            return None
        try:
            value = json.loads(match.group(0))
        except json.JSONDecodeError:
            return None
    return value if isinstance(value, dict) else None


def _coerce_confidence(value: Any, default: float) -> float:
    try:
        return _clamp_confidence(float(value))
    except (TypeError, ValueError):
        return default


def _build_explanation(
    root_cause: RootCause,
    required_satisfied: bool,
    required_matched: int,
    required_total: int,
    supporting_ids: set[str],
    negative_ids: set[str],
    causal_chain: str | None = None,
) -> str:
    status = "required evidence satisfied" if required_satisfied else f"required evidence partial {required_matched}/{required_total}"
    parts = [f"{root_cause.root_cause_id}: {status}"]
    if supporting_ids:
        parts.append(f"supporting evidence {len(supporting_ids)}건")
    if negative_ids:
        parts.append(f"negative evidence {len(negative_ids)}건 반영")
    if causal_chain:
        parts.append(causal_chain)
    return "; ".join(parts)


def _causal_chain_explanation(required_matches: list[_RuleMatch], supporting_matches: list[_RuleMatch]) -> str | None:
    chain: list[tuple[int, str, str]] = []
    for match in [*required_matches, *supporting_matches]:
        step = match.rule.causal_chain_step
        if step is None or not (match.evidence_ids or match.supporting_evidence_ids):
            continue
        status = "causal" if match.evidence_ids else "correlational"
        chain.append((step, status, match.rule.evidence))
    if not chain:
        return None
    ordered = sorted(chain, key=lambda item: item[0])
    rendered = " -> ".join(f"step {step} {status}: {evidence}" for step, status, evidence in ordered)
    return f"causal chain {rendered}"


def _sanitize_explanation(value: str) -> str:
    compact = " ".join(value.split())
    return compact[:240]


def _collect_missing_required(candidates: list[_EvaluatedCandidate]) -> list[str]:
    gaps: list[str] = []
    for candidate in candidates:
        for item in candidate.evidence_gap:
            if item not in gaps:
                gaps.append(item)
    return gaps


def _to_schema(candidate: _EvaluatedCandidate) -> RootCauseCandidate:
    return RootCauseCandidate(
        root_cause_id=candidate.root_cause_id,
        confidence=candidate.confidence,
        required_evidence_satisfied=candidate.required_evidence_satisfied,
        supporting_evidence_ids=sorted(candidate.supporting_evidence_ids),
        negative_evidence_ids=sorted(candidate.negative_evidence_ids),
        evidence_gap=candidate.evidence_gap,
        explanation=candidate.explanation,
    )


def _unknown_output(evidence_gap: list[str]) -> RcaOutput:
    unknown = get_root_cause(UNKNOWN_ROOT_CAUSE_ID)
    explanation = "catalog evidence requirements were not sufficiently satisfied"
    if unknown:
        explanation = f"{unknown.root_cause_id}: {explanation}"
    return RcaOutput(
        root_cause_candidates=[
            RootCauseCandidate(
                root_cause_id=UNKNOWN_ROOT_CAUSE_ID,
                confidence=0.0,
                required_evidence_satisfied=False,
                evidence_gap=evidence_gap,
                explanation=explanation,
            )
        ]
    )


def _clamp_confidence(value: float) -> float:
    return max(0.0, min(1.0, round(value, 3)))


def catalog_root_cause_count() -> int:
    """Expose catalog coverage for reports without duplicating catalog data."""
    return len(list_root_causes())
