"""Verifier agent for catalog evidence checks."""
from __future__ import annotations

from collections.abc import Iterable
import re
from uuid import uuid4

from app.catalogs import evidence_matrix
from app.catalogs.root_causes import is_known_root_cause
from app.schemas.outputs import (
    ClassifierOutput,
    RcaOutput,
    RetrievalOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.schemas.state import AgentMode, EvidenceItem, RootCauseCandidate, VerificationStatus


UNKNOWN_ROOT_CAUSES = {
    "UNKNOWN_WITH_EVIDENCE_GAP",
    "MULTIPLE_POSSIBLE_CAUSES",
    "CUSTOMER_OWNED_ROOT_CAUSE_LIKELY",
}


async def run_verifier(
    mode: AgentMode,
    *,
    rca_out: RcaOutput | None = None,
    retrieval_out: RetrievalOutput | None = None,
    classifier_out: ClassifierOutput | None = None,
) -> VerifierOutput:
    if mode == AgentMode.SIMPLE_QUERY:
        return _result(
            target="retrieval_result",
            status=VerificationStatus.PASS,
            reason="simple_query 경로 — 조회 결과를 그대로 통과",
        )

    if mode == AgentMode.INCIDENT_ANALYSIS:
        return _verify_incident_analysis(
            rca_out=rca_out,
            retrieval_out=retrieval_out,
            classifier_out=classifier_out,
        )

    return _result(
        target="execution_result",
        status=VerificationStatus.PASS,
        reason=f"{mode.value} 경로 — v1에서는 실행 결과 검증을 통과 처리",
    )


def _verify_incident_analysis(
    *,
    rca_out: RcaOutput | None,
    retrieval_out: RetrievalOutput | None,
    classifier_out: ClassifierOutput | None,
) -> VerifierOutput:
    del classifier_out

    candidates = rca_out.root_cause_candidates if rca_out else []
    if not candidates:
        return _needs_revision("root_cause", "RCA 후보가 없어 evidence_matrix 검증을 수행할 수 없음", "rca")

    non_catalog_ids = [
        candidate.root_cause_id
        for candidate in candidates
        if not is_known_root_cause(candidate.root_cause_id)
    ]
    if non_catalog_ids:
        return _fail(
            "root_cause",
            f"catalog 밖 root_cause_id 반환: {', '.join(non_catalog_ids)}",
            "rca",
        )

    if all(candidate.root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP" for candidate in candidates):
        return _needs_revision(
            "root_cause",
            "RCA 후보가 UNKNOWN_WITH_EVIDENCE_GAP 뿐이라 추가 evidence 필요",
            "planner",
        )

    actionable_candidates = [
        candidate for candidate in candidates if candidate.root_cause_id not in UNKNOWN_ROOT_CAUSES
    ]
    if actionable_candidates and all(
        candidate.confidence < _confidence_floor(candidate.root_cause_id)
        for candidate in actionable_candidates
    ):
        return _fail("root_cause", "모든 RCA 후보 confidence가 evidence_matrix 기준 미만", "planner")

    evidence_items = retrieval_out.evidence_items if retrieval_out else []
    evidence_texts = _observed_evidence_texts(evidence_items)
    for candidate in actionable_candidates:
        missing = _missing_required_evidence(candidate, evidence_texts)
        if missing:
            return _needs_revision(
                "root_cause",
                f"{candidate.root_cause_id} required evidence 부족: {', '.join(missing)}",
                "planner",
            )

    if any(candidate.root_cause_id in UNKNOWN_ROOT_CAUSES for candidate in candidates):
        return _needs_revision(
            "root_cause",
            "RCA 후보에 unknown 계열 root cause가 포함되어 최종 응답 승인 불가",
            "planner",
        )

    return _result(
        target="root_cause",
        status=VerificationStatus.PASS,
        reason="RCA 후보의 required evidence가 evidence_matrix 기준을 충족",
    )


def _missing_required_evidence(
    candidate: RootCauseCandidate,
    evidence_texts: tuple[str, ...],
) -> list[str]:
    required = evidence_matrix.get_required_evidence(candidate.root_cause_id)
    if not required:
        return ["evidence_matrix profile 없음"]

    missing = [
        rule.evidence
        for rule in required
        if not _rule_satisfied(rule.evidence, rule.example, evidence_texts)
    ]
    return missing


def _observed_evidence_texts(evidence_items: Iterable[EvidenceItem]) -> tuple[str, ...]:
    return tuple(
        " ".join(
            value
            for value in (
                item.evidence_id,
                str(item.type),
                item.store_ref,
                item.summary,
                item.collected_by or "",
            )
            if value
        )
        for item in evidence_items
    )


def _rule_satisfied(rule_text: str, example: str | None, evidence_texts: tuple[str, ...]) -> bool:
    needles = [_normalize(rule_text)]
    if example:
        needles.append(_normalize(example))

    for haystack in (_normalize(text) for text in evidence_texts):
        if any(needle and needle in haystack for needle in needles):
            return True
    return False


def _normalize(value: str) -> str:
    return re.sub(r"[^0-9a-zA-Z가-힣]+", " ", value).casefold().strip()


def _confidence_floor(root_cause_id: str) -> float:
    profile = evidence_matrix.get_evidence_profile(root_cause_id)
    if profile is None:
        return 1.0
    return profile.needs_more_evidence_band[0]


def _needs_revision(target: str, reason: str, next_agent: str) -> VerifierOutput:
    return _result(
        target=target,
        status=VerificationStatus.NEEDS_REVISION,
        reason=reason,
        next_agent=next_agent,
    )


def _fail(target: str, reason: str, next_agent: str) -> VerifierOutput:
    return _result(
        target=target,
        status=VerificationStatus.FAIL,
        reason=reason,
        next_agent=next_agent,
    )


def _result(
    *,
    target: str,
    status: VerificationStatus,
    reason: str,
    next_agent: str | None = None,
) -> VerifierOutput:
    return VerifierOutput(
        verification_results=[
            VerificationResultOutput(
                verification_id=str(uuid4()),
                target=target,
                status=status,
                approved_for_final_response=status == VerificationStatus.PASS,
                reason=reason,
                next_agent=next_agent,
            )
        ]
    )
