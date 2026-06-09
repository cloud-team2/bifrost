"""Remediation agent - builds action candidates from the runbook catalog."""
from __future__ import annotations

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
            _runbook_action_to_candidate(action, root_cause)
            for action in runbook_actions
        )

    return RemediationOutput(action_candidates=candidates)


def _runbook_action_to_candidate(
    action: RunbookActionTemplate,
    root_cause: RootCauseCandidate,
) -> ActionCandidateOutput:
    return ActionCandidateOutput(
        action_id=_new_action_id(),
        action_type=ActionType(action.action_type),
        action_name=action.action_name,
        root_cause_id=root_cause.root_cause_id,
        risk=RiskLevel(action.risk),
        reason=_reason(root_cause, f"runbook: {action.action_name}"),
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
