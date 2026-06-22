"""Failure Types catalog based incident classifier."""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from typing import Any

from app.catalogs.failure_types import list_failure_types
from app.catalogs.types import FailureType
from app.prompts.classifier import SYSTEM_PROMPT, build_user_prompt
from app.schemas.outputs import Classification, ClassifierOutput, IncidentTypeOutput, RetrievalOutput
from app.schemas.state import EvidenceItem, EvidenceType, IncidentScope

UNKNOWN_INCIDENT_TYPE = "UNKNOWN_NEEDS_MORE_EVIDENCE"
AMBIGUOUS_CONFIDENCE_THRESHOLD = 0.60
AMBIGUOUS_MARGIN_THRESHOLD = 0.10
MIN_RULE_CONFIDENCE = 0.35
MAX_CANDIDATES = 5

_TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣]+")
_GROUP_HINT_RE = re.compile(
    r"\b(?:pipeline|topology|connector|consumer[_ -]?group|topic)\s*[:=/#-]?\s*([0-9A-Za-z_.-]+)",
    re.IGNORECASE,
)
_GENERIC_TOKENS = {
    "and",
    "the",
    "with",
    "error",
    "failure",
    "failed",
    "spike",
    "latency",
    "timeout",
    "regression",
    "증가",
    "급증",
    "변화",
    "이후",
    "문제",
    "실패",
    "지연",
}


@dataclass(slots=True)
class _Candidate:
    incident_type: str
    confidence: float
    evidence_ids: set[str] = field(default_factory=set)


async def run_classifier(user_message: str, retrieval_out: RetrievalOutput | None) -> ClassifierOutput:
    failure_types = list_failure_types()
    known_type_ids = {item.incident_type for item in failure_types}
    evidence_items = retrieval_out.evidence_items if retrieval_out else []
    observed_evidence_items = [item for item in evidence_items if _is_observed_evidence(item)]
    evidence_summaries = [item.summary for item in observed_evidence_items]

    rule_candidates = _rank_rule_candidates(user_message, retrieval_out, failure_types)
    incident_scope = _infer_scope(evidence_summaries)
    needs_group_analysis = incident_scope == IncidentScope.INCIDENT_GROUP

    if _needs_llm_assist(rule_candidates):
        llm_candidates, llm_scope = await _run_llm_assist(
            user_message=user_message,
            evidence_summaries=evidence_summaries,
            rule_candidates=rule_candidates,
            failure_types=failure_types,
            known_type_ids=known_type_ids,
        )
        rule_candidates = _merge_candidates(rule_candidates, llm_candidates)
        if llm_scope == IncidentScope.INCIDENT_GROUP:
            incident_scope = IncidentScope.INCIDENT_GROUP
            needs_group_analysis = True

    selected = [
        candidate
        for candidate in rule_candidates
        if candidate.incident_type in known_type_ids and candidate.incident_type != UNKNOWN_INCIDENT_TYPE
    ]
    if not selected:
        selected = [_Candidate(UNKNOWN_INCIDENT_TYPE, 0.0)]

    return ClassifierOutput(
        classification=Classification(
            incident_scope=incident_scope,
            incident_types=[
                IncidentTypeOutput(
                    type=candidate.incident_type,
                    confidence=_clamp_confidence(candidate.confidence),
                    evidence_ids=sorted(candidate.evidence_ids),
                )
                for candidate in selected[:MAX_CANDIDATES]
            ],
            needs_incident_group_analysis=needs_group_analysis,
        )
    )


def _rank_rule_candidates(
    user_message: str,
    retrieval_out: RetrievalOutput | None,
    failure_types: tuple[FailureType, ...],
) -> list[_Candidate]:
    sources: list[tuple[str | None, str]] = [(None, user_message)]
    if retrieval_out:
        sources.extend(
            (item.evidence_id, item.summary)
            for item in retrieval_out.evidence_items
            if _is_observed_evidence(item)
        )

    candidates: list[_Candidate] = []
    for failure_type in failure_types:
        if failure_type.incident_type == UNKNOWN_INCIDENT_TYPE:
            continue
        confidence, evidence_ids = _score_failure_type(failure_type, sources)
        if confidence >= MIN_RULE_CONFIDENCE:
            candidates.append(_Candidate(failure_type.incident_type, confidence, evidence_ids))

    candidates.sort(key=lambda item: item.confidence, reverse=True)
    return candidates


def _score_failure_type(
    failure_type: FailureType,
    sources: list[tuple[str | None, str]],
) -> tuple[float, set[str]]:
    evidence_ids: set[str] = set()
    signal_score = 0.0
    strongest_signal_score = 0.0
    total_signals = len(failure_type.signals)

    for signal in failure_type.signals:
        best_signal_score = 0.0
        best_evidence_id: str | None = None
        signal_tokens = _tokens(signal)
        for evidence_id, text in sources:
            normalized_text = text.casefold()
            if signal.casefold() in normalized_text:
                score = 1.0
            else:
                text_tokens = set(_tokens(text))
                overlap = len(set(signal_tokens) & text_tokens)
                required = max(1, math.ceil(len(signal_tokens) * 0.5))
                score = (overlap / max(1, len(signal_tokens))) * 0.8 if overlap >= required else 0.0
            if score > best_signal_score:
                best_signal_score = score
                best_evidence_id = evidence_id
        strongest_signal_score = max(strongest_signal_score, best_signal_score)
        signal_score += best_signal_score
        if best_signal_score > 0 and best_evidence_id:
            evidence_ids.add(best_evidence_id)

    signal_confidence = signal_score / total_signals if total_signals else 0.0
    signal_confidence = max(signal_confidence, strongest_signal_score * 0.85)
    context_confidence, context_evidence_ids = _context_confidence(failure_type, sources)
    evidence_ids.update(context_evidence_ids)

    if signal_confidence > 0:
        confidence = max(signal_confidence, (signal_confidence * 0.55) + (context_confidence * 0.45))
    else:
        confidence = context_confidence
    if failure_type.incident_type == "CONNECTOR_TASK_FAILED" and _has_connector_task_failure_context(sources):
        confidence += 0.05
    return _clamp_confidence(confidence), evidence_ids


def _context_confidence(
    failure_type: FailureType,
    sources: list[tuple[str | None, str]],
) -> tuple[float, set[str]]:
    evidence_ids: set[str] = set()
    name_tokens = _meaningful_tokens(failure_type.incident_type.replace("_", " "))
    description_tokens = _meaningful_tokens(failure_type.description)
    best_score = 0.0

    for evidence_id, text in sources:
        text_tokens = set(_tokens(text))
        name_overlap = _overlap_ratio(name_tokens, text_tokens)
        description_overlap = _overlap_ratio(description_tokens, text_tokens)
        score = max(name_overlap, description_overlap) * 0.85
        if score > best_score:
            best_score = score
            evidence_ids = {evidence_id} if evidence_id else set()
        elif score > 0 and score == best_score and evidence_id:
            evidence_ids.add(evidence_id)

    return _clamp_confidence(best_score), evidence_ids


def _has_connector_task_failure_context(sources: list[tuple[str | None, str]]) -> bool:
    for _, text in sources:
        normalized = text.casefold()
        if "connector" not in normalized and "connect" not in normalized:
            continue
        if re.search(r"\btask\b.*\bfailed\b|\bfailed\b.*\btask\b", normalized):
            return True
    return False


def _needs_llm_assist(candidates: list[_Candidate]) -> bool:
    if not candidates:
        return True
    if candidates[0].confidence < AMBIGUOUS_CONFIDENCE_THRESHOLD:
        return True
    if len(candidates) > 1 and candidates[0].confidence - candidates[1].confidence < AMBIGUOUS_MARGIN_THRESHOLD:
        return True
    return False


async def _run_llm_assist(
    user_message: str,
    evidence_summaries: list[str],
    rule_candidates: list[_Candidate],
    failure_types: tuple[FailureType, ...],
    known_type_ids: set[str],
) -> tuple[list[_Candidate], IncidentScope | None]:
    candidate_ids = {candidate.incident_type for candidate in rule_candidates[:MAX_CANDIDATES]}
    prompt_candidates = [item for item in failure_types if item.incident_type in candidate_ids]
    if not prompt_candidates:
        prompt_candidates = [item for item in failure_types if item.incident_type != "CUSTOMER_OWNED_ESCALATION"]

    try:
        from app.llm.provider import get_llm_provider
    except Exception:
        return [], None

    model = _classifier_model_name()
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": build_user_prompt(user_message, evidence_summaries, prompt_candidates),
        },
    ]

    try:
        raw_response = await get_llm_provider().generate(messages, model=model)
    except Exception:
        return [], None

    parsed = _parse_json_object(raw_response)
    if not parsed:
        return [], None

    llm_candidates = _parse_llm_incident_types(parsed.get("incident_types"), known_type_ids)
    llm_scope = _parse_llm_scope(parsed)
    return llm_candidates, llm_scope


def _classifier_model_name() -> str | None:
    try:
        from app.llm.model_router import model_for_agent
    except Exception:
        return None

    try:
        return model_for_agent("classifier")
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


def _parse_llm_incident_types(value: Any, known_type_ids: set[str]) -> list[_Candidate]:
    if not isinstance(value, list):
        return []

    candidates: list[_Candidate] = []
    for item in value:
        if isinstance(item, str):
            incident_type = item
            confidence = 0.65
        elif isinstance(item, dict):
            incident_type = str(item.get("type") or item.get("incident_type") or "")
            confidence = _coerce_confidence(item.get("confidence"), default=0.65)
        else:
            continue
        if incident_type in known_type_ids and incident_type != "CUSTOMER_OWNED_ESCALATION":
            candidates.append(_Candidate(incident_type, confidence))
    candidates.sort(key=lambda item: item.confidence, reverse=True)
    return candidates


def _parse_llm_scope(parsed: dict[str, Any]) -> IncidentScope | None:
    scope = parsed.get("incident_scope")
    if scope == IncidentScope.INCIDENT_GROUP.value or parsed.get("needs_incident_group_analysis") is True:
        return IncidentScope.INCIDENT_GROUP
    if scope == IncidentScope.SINGLE.value:
        return IncidentScope.SINGLE
    return None


def _merge_candidates(rule_candidates: list[_Candidate], llm_candidates: list[_Candidate]) -> list[_Candidate]:
    by_type = {candidate.incident_type: candidate for candidate in rule_candidates}
    for llm_candidate in llm_candidates:
        existing = by_type.get(llm_candidate.incident_type)
        if existing:
            existing.confidence = max(existing.confidence, llm_candidate.confidence)
        else:
            by_type[llm_candidate.incident_type] = llm_candidate
    candidates = list(by_type.values())
    candidates.sort(key=lambda item: item.confidence, reverse=True)
    return candidates


def _is_observed_evidence(item: EvidenceItem) -> bool:
    return item.type != EvidenceType.KNOWLEDGE and item.type != EvidenceType.KNOWLEDGE.value


def _infer_scope(evidence_summaries: list[str]) -> IncidentScope:
    resource_ids: set[str] = set()
    for summary in evidence_summaries:
        for match in _GROUP_HINT_RE.finditer(summary):
            resource_ids.add(match.group(1).casefold())
    if len(resource_ids) > 1:
        return IncidentScope.INCIDENT_GROUP
    return IncidentScope.SINGLE


def _tokens(value: str) -> list[str]:
    return [token.casefold() for token in _TOKEN_RE.findall(value)]


def _meaningful_tokens(value: str) -> set[str]:
    return {token for token in _tokens(value) if len(token) > 1 and token not in _GENERIC_TOKENS}


def _overlap_ratio(expected: set[str], actual: set[str]) -> float:
    if not expected:
        return 0.0
    return len(expected & actual) / len(expected)


def _coerce_confidence(value: Any, default: float) -> float:
    try:
        return _clamp_confidence(float(value))
    except (TypeError, ValueError):
        return default


def _clamp_confidence(value: float) -> float:
    return max(0.0, min(1.0, round(value, 3)))
