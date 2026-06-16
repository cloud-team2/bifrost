"""RCA assistant backed by root-cause and evidence catalogs."""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, replace
from typing import Any

from app.catalogs.evidence_matrix import get_evidence_profile
from app.catalogs.incident_rootcause_map import get_root_cause_candidates
from app.catalogs.root_causes import get_root_cause, list_root_causes
from app.catalogs.types import EvidenceProfile, EvidenceRule, RootCause
from app.prompts.rca import SYSTEM_PROMPT, build_user_prompt
from app.schemas.outputs import ClassifierOutput, RcaOutput, RetrievalOutput
from app.schemas.state import EvidenceItem, RootCauseCandidate

UNKNOWN_ROOT_CAUSE_ID = "UNKNOWN_WITH_EVIDENCE_GAP"
UNKNOWN_INCIDENT_TYPE = "UNKNOWN_NEEDS_MORE_EVIDENCE"
MIN_CONFIDENT_ROOT_CAUSE = 0.60
LLM_TIE_MARGIN = 0.10
MAX_CANDIDATES = 5

_TOKEN_RE = re.compile(r"[0-9A-Za-z가-힣]+")
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
    "존재",
    "또는",
    "단계",
}


@dataclass(frozen=True, slots=True)
class _RuleMatch:
    rule: EvidenceRule
    evidence_ids: set[str]


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


async def run_rca(classifier_out: ClassifierOutput | None, retrieval_out: RetrievalOutput | None) -> RcaOutput:
    evidence_items = retrieval_out.evidence_items if retrieval_out else []
    candidate_ids = _candidate_pool(classifier_out)

    if not evidence_items:
        return _unknown_output(["evidence_items_empty"])
    if candidate_ids == [UNKNOWN_ROOT_CAUSE_ID]:
        return _unknown_output(["classifier_unknown_needs_more_evidence"])
    if not candidate_ids:
        return _unknown_output(["classifier_incident_type_mapping_missing"])

    candidates = [
        candidate
        for candidate_id in candidate_ids
        if (candidate := _evaluate_candidate(candidate_id, evidence_items)) is not None
    ]
    candidates.sort(key=lambda item: item.confidence, reverse=True)

    if _needs_llm_assist(candidates):
        llm_selection = await _run_llm_assist(candidates, evidence_items, _classifier_types(classifier_out))
        if llm_selection:
            candidates = _apply_llm_selection(candidates, llm_selection)

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


def _evaluate_candidate(root_cause_id: str, evidence_items: list[EvidenceItem]) -> _EvaluatedCandidate | None:
    root_cause = get_root_cause(root_cause_id)
    profile = get_evidence_profile(root_cause_id)
    if root_cause is None or profile is None:
        return None

    required_matches = _match_rules(profile.required, evidence_items)
    supporting_matches = _match_rules(profile.supporting, evidence_items)
    negative_matches = _match_rules(profile.negative, evidence_items)
    matched_required = [item for item in required_matches if item.evidence_ids]
    evidence_gap = [item.rule.evidence for item in required_matches if not item.evidence_ids]

    required_satisfied = not evidence_gap
    supporting_ids = _matched_evidence_ids(supporting_matches)
    negative_ids = _matched_evidence_ids(negative_matches)
    confidence = _score_confidence(
        root_cause=root_cause,
        required_matched=len(matched_required),
        required_total=len(profile.required),
        supporting_matched=sum(1 for item in supporting_matches if item.evidence_ids),
        supporting_total=len(profile.supporting),
        negative_count=len(negative_ids),
    )

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
        ),
    )


def _match_rules(rules: tuple[EvidenceRule, ...], evidence_items: list[EvidenceItem]) -> list[_RuleMatch]:
    return [
        _RuleMatch(
            rule=rule,
            evidence_ids={
                item.evidence_id
                for item in evidence_items
                if _rule_matches_evidence(rule, item)
            },
        )
        for rule in rules
    ]


def _rule_matches_evidence(rule: EvidenceRule, item: EvidenceItem) -> bool:
    evidence_text = f"{item.type} {item.summary}".casefold()
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


def _split_alternatives(value: str) -> list[str]:
    parts = re.split(r"[,，]", value)
    return [part.strip() for part in parts if part.strip()]


def _tokens_match(rule_tokens: set[str], evidence_tokens: set[str]) -> bool:
    if not rule_tokens:
        return False
    overlap = len(rule_tokens & evidence_tokens)
    required = len(rule_tokens) if len(rule_tokens) <= 2 else math.ceil(len(rule_tokens) * 0.5)
    return overlap >= required


def _tokens(value: str) -> list[str]:
    return [token.casefold() for token in _TOKEN_RE.findall(value)]


def _meaningful_tokens(value: str) -> set[str]:
    return {token for token in _tokens(value) if len(token) > 1 and token not in _GENERIC_TOKENS}


def _matched_evidence_ids(matches: list[_RuleMatch]) -> set[str]:
    evidence_ids: set[str] = set()
    for match in matches:
        evidence_ids.update(match.evidence_ids)
    return evidence_ids


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
        confidence = 0.82 + (supporting_ratio * 0.10)
    elif required_matched > 0:
        confidence = 0.60 + (required_ratio * 0.16) + (supporting_ratio * 0.04)
        confidence = min(confidence, root_cause.default_confidence_cap)
    else:
        confidence = min(0.35 + (supporting_ratio * 0.10), root_cause.default_confidence_cap, 0.59)

    confidence -= 0.10 * negative_count
    if required_total and required_matched < required_total:
        confidence = min(confidence, root_cause.default_confidence_cap)
    return _clamp_confidence(confidence)


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
                evidence_summaries=[(item.evidence_id, item.summary) for item in evidence_items],
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
) -> str:
    status = "required evidence satisfied" if required_satisfied else f"required evidence partial {required_matched}/{required_total}"
    parts = [f"{root_cause.root_cause_id}: {status}"]
    if supporting_ids:
        parts.append(f"supporting evidence {len(supporting_ids)}건")
    if negative_ids:
        parts.append(f"negative evidence {len(negative_ids)}건 반영")
    return "; ".join(parts)


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
