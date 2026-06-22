"""Safe live fault specs for connector-only RCA eval.

This catalog is intentionally separate from live_fault_specs.py. It never carries
kubectl, scale, DB exec, or SQL mutation steps. The runner injects by calling the
operations-backend safe-injection API, which creates only labelled test resources.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.catalogs.root_causes import ROOT_CAUSE_INDEX


@dataclass(frozen=True)
class SafeFaultSpec:
    fault_id: str
    description: str
    expected_root_cause_ids: tuple[str, ...]

    @property
    def primary_root_cause_id(self) -> str:
        return self.expected_root_cause_ids[0]


SAFE_FAULT_SPECS: tuple[SafeFaultSpec, ...] = (
    SafeFaultSpec(
        fault_id="auth",
        description="new labelled test connector with invalid sink credentials",
        expected_root_cause_ids=("SINK_AUTH_EXPIRED", "CREDENTIAL_ROTATION_REGRESSION"),
    ),
    SafeFaultSpec(
        fault_id="schema",
        description="new labelled test connector with incompatible converter config",
        expected_root_cause_ids=("SCHEMA_MISMATCH", "PIPELINE_CONFIG_INVALID"),
    ),
    SafeFaultSpec(
        fault_id="lag",
        description="new labelled paused test connector to exercise lag-style RCA path",
        expected_root_cause_ids=("CONSUMER_LAG_SPIKE",),
    ),
    SafeFaultSpec(
        fault_id="sink-fail",
        description="new labelled test connector with nonexistent sink target",
        expected_root_cause_ids=("SINK_DB_CONNECTION_TIMEOUT", "CONNECTOR_TASK_FAILED"),
    ),
    SafeFaultSpec(
        fault_id="no-fault",
        description="new labelled paused control connector without a fault expectation",
        expected_root_cause_ids=("UNKNOWN_WITH_EVIDENCE_GAP",),
    ),
)


def _validate() -> None:
    seen: set[str] = set()
    for spec in SAFE_FAULT_SPECS:
        if spec.fault_id in seen:
            raise ValueError(f"duplicate safe fault id: {spec.fault_id}")
        seen.add(spec.fault_id)
        for root_cause_id in spec.expected_root_cause_ids:
            if root_cause_id not in ROOT_CAUSE_INDEX:
                raise ValueError(f"{spec.fault_id}: unknown root cause {root_cause_id}")


_validate()

def list_safe_fault_specs() -> tuple[SafeFaultSpec, ...]:
    return SAFE_FAULT_SPECS
