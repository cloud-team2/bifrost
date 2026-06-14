"""Catalog data <-> docs spec consistency regression tests."""
from __future__ import annotations

import re
from pathlib import Path

from app.catalogs.runbooks import list_runbooks


CONNECTOR_RESTART_EXPECTED = {
    "action_name": "restart_connector",
    "action_type": "runtime_tool",
    "risk": "medium",
    "tool_name": "restart_connector",
}


def _policy_matrix_risks() -> dict[str, str]:
    policy_doc = (
        Path(__file__).resolve().parents[3]
        / "docs/design/backend-fastapi/catalog/catalog-policy-matrix.md"
    )
    risks: dict[str, str] = {}
    for line in policy_doc.read_text().splitlines():
        match = re.match(r"\| `([^`]+)` \| ([^| ]+) \| [^| ]+ \|", line)
        if match:
            risks[match.group(1)] = match.group(2)
    return risks


def test_restart_connector_matches_docs() -> None:
    """restart_connector risk/tool_name matches docs."""
    found = []
    for runbook in list_runbooks():
        for action in runbook.actions:
            if action.action_name == "restart_connector":
                found.append(action)

    assert len(found) >= 1, "restart_connector action not found in any runbook"
    for action in found:
        assert action.action_type == CONNECTOR_RESTART_EXPECTED["action_type"]
        assert action.risk == CONNECTOR_RESTART_EXPECTED["risk"], (
            f"risk mismatch in runbook (root_cause check needed): {action.risk}"
        )
        assert action.tool_name == CONNECTOR_RESTART_EXPECTED["tool_name"], (
            f"tool_name mismatch: {action.tool_name}"
        )


def test_runtime_tool_action_has_tool_name() -> None:
    """All runtime_tool actions require tool_name (output schema validator consistency)."""
    for runbook in list_runbooks():
        for action in runbook.actions:
            if action.action_type == "runtime_tool":
                assert action.tool_name, f"runtime_tool '{action.action_name}' missing tool_name"


def test_runbook_action_risks_match_policy_matrix_docs() -> None:
    """Runbook action risk values follow catalog-policy-matrix.md when listed."""
    expected_risks = _policy_matrix_risks()

    for runbook in list_runbooks():
        for action in runbook.actions:
            expected = expected_risks.get(action.action_name)
            if expected is None:
                continue
            assert action.risk == expected, (
                f"{runbook.root_cause_id}.{action.action_name} risk mismatch: "
                f"{action.risk} != {expected}"
            )
