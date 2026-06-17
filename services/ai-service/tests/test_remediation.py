"""Tests for the remediation agent runbook mapping."""
from __future__ import annotations

import pytest

from app.agents import remediation
from app.catalogs.runbooks import get_actions_for_root_cause, list_runbooks
from app.schemas.outputs import RcaOutput, RemediationOutput
from app.schemas.state import ActionType, RiskLevel, RootCauseCandidate


def _root_cause(root_cause_id: str, explanation: str = "test explanation") -> RootCauseCandidate:
    return RootCauseCandidate(
        root_cause_id=root_cause_id,
        confidence=0.82,
        required_evidence_satisfied=True,
        supporting_evidence_ids=["ev1"],
        negative_evidence_ids=[],
        evidence_gap=[],
        explanation=explanation,
    )


def _rca_out(*root_cause_ids: str) -> RcaOutput:
    return RcaOutput(root_cause_candidates=[_root_cause(root_cause_id) for root_cause_id in root_cause_ids])


def _catalog_action_names() -> set[str]:
    return {
        action.action_name
        for runbook in list_runbooks()
        for action in runbook.actions
    }


@pytest.mark.asyncio
async def test_connector_task_failed_runbook() -> None:
    output = await remediation.run_remediation(_rca_out("CONNECTOR_TASK_FAILED"))
    assert [candidate.action_name for candidate in output.action_candidates] == ["restart_connector"]
    restart = output.action_candidates[0]
    catalog_restart = next(
        action
        for action in get_actions_for_root_cause("CONNECTOR_TASK_FAILED")
        if action.action_name == "restart_connector"
    )

    assert restart.action_type == ActionType.RUNTIME_TOOL
    assert restart.risk == RiskLevel(catalog_restart.risk)
    assert restart.tool_name == catalog_restart.tool_name
    assert restart.root_cause_id == "CONNECTOR_TASK_FAILED"
    assert "runbook: restart_connector" in restart.reason


@pytest.mark.asyncio
async def test_connector_task_failed_does_not_bulk_list_lifecycle_actions() -> None:
    output = await remediation.run_remediation(_rca_out("CONNECTOR_TASK_FAILED"))

    runtime_tools = [
        candidate.tool_name
        for candidate in output.action_candidates
        if candidate.action_type == ActionType.RUNTIME_TOOL
    ]
    assert runtime_tools == ["restart_connector"]


@pytest.mark.asyncio
async def test_connector_task_failed_pause_requires_repeated_impact_context() -> None:
    output = await remediation.run_remediation(
        RcaOutput(
            root_cause_candidates=[
                _root_cause(
                    "CONNECTOR_TASK_FAILED",
                    explanation="CONNECTOR_TASK_FAILED: 반복 실패로 downstream 영향이 커짐",
                )
            ]
        )
    )

    assert [candidate.action_name for candidate in output.action_candidates] == ["pause_connector"]
    assert "repeated impact" in output.action_candidates[0].reason


@pytest.mark.asyncio
async def test_connector_task_failed_resume_requires_resolved_context() -> None:
    output = await remediation.run_remediation(
        RcaOutput(
            root_cause_candidates=[
                _root_cause(
                    "CONNECTOR_TASK_FAILED",
                    explanation="CONNECTOR_TASK_FAILED: 원인 해소 후 재개 필요",
                )
            ]
        )
    )

    assert [candidate.action_name for candidate in output.action_candidates] == ["resume_connector"]
    assert "cause is resolved" in output.action_candidates[0].reason


@pytest.mark.asyncio
async def test_runtime_tool_requires_tool_name() -> None:
    output = await remediation.run_remediation(_rca_out("CONNECTOR_TASK_FAILED", "CONSUMER_LAG_SPIKE"))

    runtime_candidates = [
        candidate
        for candidate in output.action_candidates
        if candidate.action_type == ActionType.RUNTIME_TOOL
    ]
    assert runtime_candidates
    assert all(candidate.tool_name for candidate in runtime_candidates)


@pytest.mark.asyncio
async def test_customer_owned_root_cause_escalation() -> None:
    output = await remediation.run_remediation(_rca_out("SOURCE_DB_CONNECTION_TIMEOUT"))

    assert [candidate.action_name for candidate in output.action_candidates] == [
        "escalate_to_customer_owner"
    ]
    assert output.action_candidates[0].action_type == ActionType.ESCALATION
    assert output.action_candidates[0].risk == RiskLevel.LOW


@pytest.mark.asyncio
async def test_no_runbook_fallback_escalation() -> None:
    output = await remediation.run_remediation(_rca_out("ROOT_CAUSE_WITHOUT_RUNBOOK"))

    assert len(output.action_candidates) == 1
    assert output.action_candidates[0].action_name == "escalate_to_operator"
    assert output.action_candidates[0].action_type == ActionType.ESCALATION


@pytest.mark.asyncio
async def test_unknown_passes_to_escalation() -> None:
    output = await remediation.run_remediation(_rca_out("UNKNOWN_WITH_EVIDENCE_GAP"))

    assert len(output.action_candidates) == 1
    assert output.action_candidates[0].action_name == "escalate_to_operator"
    assert output.action_candidates[0].root_cause_id == "UNKNOWN_WITH_EVIDENCE_GAP"


@pytest.mark.asyncio
async def test_customer_owned_likely_passes_to_customer_owner_escalation() -> None:
    output = await remediation.run_remediation(_rca_out("CUSTOMER_OWNED_ROOT_CAUSE_LIKELY"))

    assert len(output.action_candidates) == 1
    assert output.action_candidates[0].action_name == "escalate_to_customer_owner"


@pytest.mark.asyncio
async def test_no_rca_output() -> None:
    none_output = await remediation.run_remediation(None)
    empty_output = await remediation.run_remediation(RcaOutput(root_cause_candidates=[]))

    assert none_output.action_candidates[0].action_name == "escalate_to_operator"
    assert none_output.action_candidates[0].root_cause_id is None
    assert empty_output.action_candidates[0].action_name == "escalate_to_operator"


@pytest.mark.asyncio
async def test_catalog_only_action_names() -> None:
    output = await remediation.run_remediation(
        _rca_out(
            "CONNECTOR_TASK_FAILED",
            "SOURCE_DB_CONNECTION_TIMEOUT",
            "UNKNOWN_WITH_EVIDENCE_GAP",
            "ROOT_CAUSE_WITHOUT_RUNBOOK",
        )
    )

    catalog_action_names = _catalog_action_names()
    assert output.action_candidates
    assert {
        candidate.action_name
        for candidate in output.action_candidates
    } <= catalog_action_names


@pytest.mark.asyncio
async def test_llm_unavailable_rule_only() -> None:
    output = await remediation.run_remediation(_rca_out("CONNECTOR_TASK_FAILED"))

    assert output.action_candidates
    assert any(candidate.action_name == "restart_connector" for candidate in output.action_candidates)


@pytest.mark.asyncio
async def test_schema_extra_forbid() -> None:
    output = await remediation.run_remediation(_rca_out("CONNECTOR_TASK_FAILED"))

    revalidated = RemediationOutput.model_validate(output.model_dump())
    assert revalidated == output
