"""Verifier agent for catalog evidence checks."""
from __future__ import annotations

from collections.abc import Iterable
import re
from uuid import uuid4

from app.catalogs import evidence_matrix
from app.catalogs.root_causes import is_known_root_cause, root_cause_ids
from app.schemas.outputs import (
    ClassifierOutput,
    ExecutorOutput,
    RcaOutput,
    RetrievalOutput,
    VerificationResultOutput,
    VerifierOutput,
)
from app.schemas.state import (
    ActionStatus,
    AgentMode,
    EvidenceItem,
    RootCauseCandidate,
    VerificationStatus,
)


UNKNOWN_ROOT_CAUSES = {
    "UNKNOWN_WITH_EVIDENCE_GAP",
    "MULTIPLE_POSSIBLE_CAUSES",
    "CUSTOMER_OWNED_ROOT_CAUSE_LIKELY",
}
_ROOT_CAUSE_REF_RE = re.compile(
    r"\broot[_ -]?cause(?:[_ -]?id)?\s*(?:[:=]|is|는|은)?\s*([A-Z0-9_]+)\b",
    re.IGNORECASE,
)
_SUCCESS_WORDS = ("success", "succeeded", "completed", "ok", "성공", "완료")


async def run_verifier(
    mode: AgentMode,
    *,
    rca_out: RcaOutput | None = None,
    retrieval_out: RetrievalOutput | None = None,
    classifier_out: ClassifierOutput | None = None,
    executor_out: ExecutorOutput | None = None,
    report_body: str | None = None,
) -> VerifierOutput:
    if (
        report_body is not None
        and rca_out is None
        and retrieval_out is None
        and executor_out is None
    ):
        return _verify_report_body(report_body=report_body, rca_out=rca_out)

    if mode == AgentMode.SIMPLE_QUERY:
        if report_body is not None:
            return _verify_report_body(report_body=report_body, rca_out=rca_out)
        return _result(
            target="retrieval_result",
            status=VerificationStatus.PASS,
            reason="simple_query 경로 — 조회 결과를 그대로 통과",
        )

    if mode == AgentMode.INCIDENT_ANALYSIS:
        incident_result = _verify_incident_analysis(
            rca_out=rca_out,
            retrieval_out=retrieval_out,
            classifier_out=classifier_out,
        )
        first = incident_result.verification_results[0]
        if first.status != VerificationStatus.PASS or report_body is None:
            return incident_result
        return _verify_report_body(report_body=report_body, rca_out=rca_out)

    if mode in (AgentMode.ACTION_EXECUTION, AgentMode.APPROVAL_DECISION):
        execution_result = _verify_action_execution(executor_out=executor_out)
        first = execution_result.verification_results[0]
        if first.status != VerificationStatus.PASS or report_body is None:
            return execution_result
        return _verify_report_body(report_body=report_body, rca_out=rca_out)

    return _result(
        target="execution_result",
        status=VerificationStatus.FAIL,
        reason=f"지원하지 않는 verifier mode: {mode.value}",
        next_agent="planner",
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


def _verify_action_execution(
    *,
    executor_out: ExecutorOutput | None,
) -> VerifierOutput:
    if executor_out is None or not executor_out.execution_results:
        return _needs_revision("execution_result", "Executor 실행 결과가 없어 검증할 수 없음", "executor")

    for result in executor_out.execution_results:
        if not result.after_evidence_id:
            return _needs_revision(
                "execution_result",
                f"Executor 결과에 after evidence가 없음: action_id={result.action_id}",
                "executor",
            )

        if result.status in (ActionStatus.FAILED, ActionStatus.BLOCKED):
            if not result.reason_code and not result.summary.strip():
                return _needs_revision(
                    "execution_result",
                    f"실패/차단 결과의 사유가 비어 있음: action_id={result.action_id}",
                    "executor",
                )
            summary = result.summary.casefold()
            if not result.reason_code and any(word in summary for word in _SUCCESS_WORDS):
                return _needs_revision(
                    "execution_result",
                    f"실패/차단 status와 summary가 불일치함: action_id={result.action_id}",
                    "executor",
                )

    return _result(
        target="execution_result",
        status=VerificationStatus.PASS,
        reason="Executor 결과의 after evidence와 실패/차단 사유가 검증됨",
    )


def _verify_report_body(
    *,
    report_body: str,
    rca_out: RcaOutput | None,
) -> VerifierOutput:
    if not report_body.strip():
        return _needs_revision("report", "Report 본문이 비어 있음", "report")

    mentioned = _mentioned_root_cause_ids(report_body)
    non_catalog = [
        root_cause_id
        for root_cause_id in mentioned
        if not is_known_root_cause(root_cause_id)
    ]
    if non_catalog:
        return _fail(
            "report",
            f"Report가 catalog 밖 root_cause_id를 포함함: {', '.join(non_catalog)}",
            "report",
        )

    allowed = {
        candidate.root_cause_id
        for candidate in (rca_out.root_cause_candidates if rca_out else [])
    }
    if mentioned and not allowed:
        return _needs_revision(
            "report",
            f"RCA 검증 없이 Report가 root cause 결론을 포함함: {', '.join(mentioned)}",
            "report",
        )

    unverified = [root_cause_id for root_cause_id in mentioned if root_cause_id not in allowed]
    if unverified:
        return _needs_revision(
            "report",
            f"Report가 검증되지 않은 root cause 결론을 포함함: {', '.join(unverified)}",
            "report",
        )

    return _result(
        target="report",
        status=VerificationStatus.PASS,
        reason="Report 본문이 검증된 catalog/RCA 범위 안에 있음",
    )


def _mentioned_root_cause_ids(report_body: str) -> list[str]:
    known_ids = set(root_cause_ids())
    seen: set[str] = set()
    ordered: list[str] = []

    for match in _ROOT_CAUSE_REF_RE.finditer(report_body):
        root_cause_id = match.group(1).upper()
        if root_cause_id not in seen:
            seen.add(root_cause_id)
            ordered.append(root_cause_id)

    for root_cause_id in sorted(known_ids):
        if root_cause_id in report_body and root_cause_id not in seen:
            seen.add(root_cause_id)
            ordered.append(root_cause_id)

    return ordered


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
