"""Remediation agent - builds action candidates from the runbook catalog."""
from __future__ import annotations

import re
from uuid import uuid4

from app.catalogs.root_causes import get_root_cause
from app.catalogs.runbooks import get_actions_for_root_cause, list_runbooks
from app.catalogs.types import RunbookActionTemplate
from app.schemas.outputs import ActionCandidateOutput, RcaOutput, RemediationOutput
from app.schemas.state import ActionType, RiskLevel, RootCauseCandidate

_UNKNOWN_ESCALATION_ROOT_CAUSES = {
    "UNKNOWN_WITH_EVIDENCE_GAP",
    "MULTIPLE_POSSIBLE_CAUSES",
}
_CUSTOMER_ESCALATION_ROOT_CAUSES = {
    "CUSTOMER_OWNED_ROOT_CAUSE_LIKELY",
}
_OPERATOR_ESCALATION_ACTION = "escalate_to_operator"
_CUSTOMER_ESCALATION_ACTION = "escalate_to_customer_owner"
_CONNECTOR_TASK_FAILED = "CONNECTOR_TASK_FAILED"
_CONNECTOR_TRACE_ACTION = "collect_connector_trace"
_CONNECTOR_RESTART_ACTION = "restart_connector"
_CONNECTOR_PAUSE_ACTION = "pause_connector"
_CONNECTOR_RESUME_ACTION = "resume_connector"
_PAUSE_HINTS = (
    "repeated",
    "recurring",
    "repeat",
    "loop",
    "flap",
    "downstream noise",
    "customer impact",
    "반복",
    "계속",
    "영향",
    "확산",
    "노이즈",
)
_RESUME_HINTS = (
    "resolved",
    "recovered",
    "fixed",
    "cleared",
    "resume",
    "복구",
    "해소",
    "조치 완료",
    "재개",
)
_TRACE_GAP_HINTS = (
    "task trace",
    "worker log",
    "trace",
    "worker",
)
_FAILED_STATUS_GAP_HINTS = (
    "connector task status",
    "status `failed`",
    "task 상태",
)
_NEGATED_RESUME_PATTERNS = (
    r"\b(?:do\s+not|don't|should\s+not|cannot|can't|never|not)\s+(?:\w+\s+){0,3}resume\b",
    r"\bnot\s+(?:resolved|recovered|fixed|cleared)\b",
    r"(?:복구|해소)(?:되지|되지 않| 안| 미완| 전)",
    r"재개\s*(?:금지|불가|보류|하지\s*마|하면\s*안)",
)


async def run_remediation(rca_out: RcaOutput | None) -> RemediationOutput:
    if rca_out is None or not rca_out.root_cause_candidates:
        return RemediationOutput(
            action_candidates=[
                _fallback_candidate(
                    action_name=_OPERATOR_ESCALATION_ACTION,
                    root_cause_id=None,
                    reason="RCA result is empty - operator review required",
                )
            ]
        )

    candidates: list[ActionCandidateOutput] = []
    for root_cause in rca_out.root_cause_candidates:
        if root_cause.root_cause_id in _CUSTOMER_ESCALATION_ROOT_CAUSES:
            candidates.append(_customer_owner_escalation(root_cause))
            continue

        if root_cause.root_cause_id in _UNKNOWN_ESCALATION_ROOT_CAUSES:
            candidates.append(_operator_escalation(root_cause, "root cause is not confirmed"))
            continue

        root_cause_def = get_root_cause(root_cause.root_cause_id)
        if root_cause_def and "customer" in root_cause_def.owned_by:
            candidates.append(_customer_owner_escalation(root_cause))
            continue

        runbook_actions = get_actions_for_root_cause(root_cause.root_cause_id)
        if not runbook_actions:
            candidates.append(_operator_escalation(root_cause, "no remediation runbook is available"))
            continue

        candidates.extend(
            _runbook_action_to_candidate(action, root_cause, reason_suffix=reason_suffix)
            for action, reason_suffix in _select_runbook_actions(runbook_actions, root_cause)
        )

    return RemediationOutput(action_candidates=candidates)


def _select_runbook_actions(
    actions: tuple[RunbookActionTemplate, ...],
    root_cause: RootCauseCandidate,
) -> list[tuple[RunbookActionTemplate, str]]:
    if root_cause.root_cause_id == _CONNECTOR_TASK_FAILED:
        return _select_connector_task_actions(actions, root_cause)
    return [(action, f"runbook: {action.action_name}") for action in actions]


def _select_connector_task_actions(
    actions: tuple[RunbookActionTemplate, ...],
    root_cause: RootCauseCandidate,
) -> list[tuple[RunbookActionTemplate, str]]:
    by_name = {action.action_name: action for action in actions}
    context = _candidate_context(root_cause)

    if (
        not root_cause.required_evidence_satisfied
        and _gap_has_hint(root_cause.evidence_gap, _FAILED_STATUS_GAP_HINTS)
        and _CONNECTOR_TRACE_ACTION in by_name
    ):
        return [(
            by_name[_CONNECTOR_TRACE_ACTION],
            "runbook: collect_connector_trace; selected because failed status evidence is still missing",
        )]

    if _has_hint(context, _PAUSE_HINTS) and _CONNECTOR_PAUSE_ACTION in by_name:
        return [(
            by_name[_CONNECTOR_PAUSE_ACTION],
            "runbook: pause_connector; selected because context indicates repeated impact",
        )]

    if _has_resume_context(context) and _CONNECTOR_RESUME_ACTION in by_name:
        return [(
            by_name[_CONNECTOR_RESUME_ACTION],
            "runbook: resume_connector; selected because context indicates the cause is resolved",
        )]

    if (
        not root_cause.required_evidence_satisfied
        and _gap_has_hint(root_cause.evidence_gap, _TRACE_GAP_HINTS)
        and _CONNECTOR_TRACE_ACTION in by_name
    ):
        return [(
            by_name[_CONNECTOR_TRACE_ACTION],
            "runbook: collect_connector_trace; selected because trace evidence is still missing",
        )]

    if _CONNECTOR_RESTART_ACTION in by_name:
        return [(
            by_name[_CONNECTOR_RESTART_ACTION],
            "runbook: restart_connector; selected for failed connector task recovery",
        )]

    return [(action, f"runbook: {action.action_name}") for action in actions[:1]]


def _candidate_context(root_cause: RootCauseCandidate) -> str:
    return " ".join(
        part
        for part in [root_cause.explanation, *root_cause.evidence_gap]
        if part
    ).casefold()


def _has_hint(context: str, hints: tuple[str, ...]) -> bool:
    return any(hint.casefold() in context for hint in hints)


def _has_resume_context(context: str) -> bool:
    if any(re.search(pattern, context, flags=re.IGNORECASE) for pattern in _NEGATED_RESUME_PATTERNS):
        return False
    return _has_hint(context, _RESUME_HINTS)


def _gap_has_hint(evidence_gap: list[str], hints: tuple[str, ...]) -> bool:
    gap_context = " ".join(evidence_gap).casefold()
    return _has_hint(gap_context, hints)


def _runbook_action_to_candidate(
    action: RunbookActionTemplate,
    root_cause: RootCauseCandidate,
    *,
    reason_suffix: str | None = None,
) -> ActionCandidateOutput:
    return ActionCandidateOutput(
        action_id=_new_action_id(),
        action_type=ActionType(action.action_type),
        action_name=action.action_name,
        root_cause_id=root_cause.root_cause_id,
        risk=RiskLevel(action.risk),
        reason=_reason(root_cause, reason_suffix or f"runbook: {action.action_name}"),
        expected_effect=action.description,
        rollback_plan=action.rollback_plan,
        estimated_duration=action.estimated_duration,
        tool_name=action.tool_name,
    )


def _operator_escalation(root_cause: RootCauseCandidate, reason_prefix: str) -> ActionCandidateOutput:
    return _fallback_candidate(
        action_name=_OPERATOR_ESCALATION_ACTION,
        root_cause_id=root_cause.root_cause_id,
        reason=_reason(root_cause, reason_prefix),
    )


def _customer_owner_escalation(root_cause: RootCauseCandidate) -> ActionCandidateOutput:
    return _fallback_candidate(
        action_name=_CUSTOMER_ESCALATION_ACTION,
        root_cause_id=root_cause.root_cause_id,
        reason=_reason(root_cause, "customer-owned root cause - escalate to customer owner"),
    )


def _fallback_candidate(
    *,
    action_name: str,
    root_cause_id: str | None,
    reason: str,
) -> ActionCandidateOutput:
    action = _catalog_action_by_name(action_name)
    if action is None:
        raise RuntimeError(f"missing remediation catalog action: {action_name}")

    return ActionCandidateOutput(
        action_id=_new_action_id(),
        action_type=ActionType(action.action_type),
        action_name=action.action_name,
        root_cause_id=root_cause_id,
        risk=RiskLevel(action.risk),
        reason=reason,
        expected_effect=action.description,
        rollback_plan=action.rollback_plan,
        estimated_duration=action.estimated_duration,
        tool_name=action.tool_name,
    )


def _catalog_action_by_name(action_name: str) -> RunbookActionTemplate | None:
    for runbook in list_runbooks():
        for action in runbook.actions:
            if action.action_name == action_name:
                return action
    return None


def _reason(root_cause: RootCauseCandidate, suffix: str) -> str:
    explanation = root_cause.explanation.strip()
    if not explanation:
        return suffix
    return f"{explanation} | {suffix}"


def _new_action_id() -> str:
    return f"act_{uuid4().hex[:10]}"
