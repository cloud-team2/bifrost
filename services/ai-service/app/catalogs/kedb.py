"""Known Error Database seed records linked to root-cause catalog (#894)."""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class KedbRecord:
    root_cause_id: str
    owner: str
    known_symptoms: tuple[str, ...] = field(default_factory=tuple)
    verified_fixes: tuple[str, ...] = field(default_factory=tuple)
    rollback_procedure: str | None = None
    recurrence_count: int = 0
    last_seen: str | None = None
    incident_links: tuple[str, ...] = field(default_factory=tuple)


STATIC_KEDB_RECORDS: tuple[KedbRecord, ...] = (
    KedbRecord(
        root_cause_id="CONNECTOR_TASK_FAILED",
        owner="bifrost",
        known_symptoms=("connector task status FAILED", "worker log contains task exception"),
        verified_fixes=("collect connector trace", "restart connector after transient failure"),
        rollback_procedure="resume previous connector configuration and restart connector",
        recurrence_count=2,
        last_seen="2026-06-15",
        incident_links=("incident://2026-connector-task-failed-001",),
    ),
    KedbRecord(
        root_cause_id="SOURCE_AUTH_EXPIRED",
        owner="customer/shared",
        known_symptoms=("source auth/permission error", "token expired or access denied"),
        verified_fixes=("rotate source credential through owner process", "verify connector can authenticate"),
        rollback_procedure="restore previous valid secret version when rotation is faulty",
        recurrence_count=2,
        last_seen="2026-06-15",
        incident_links=("incident://2026-source-auth-expired-001",),
    ),
    KedbRecord(
        root_cause_id="SCHEMA_MISMATCH",
        owner="shared",
        known_symptoms=("deserialization error", "schema compatibility failure"),
        verified_fixes=("pause affected pipeline", "restore compatible schema or transform mapping"),
        rollback_procedure="rollback schema/transform change through change management",
        recurrence_count=2,
        last_seen="2026-06-15",
        incident_links=("incident://2026-schema-mismatch-001",),
    ),
    KedbRecord(
        root_cause_id="CONSUMER_LAG_SPIKE",
        owner="bifrost",
        known_symptoms=("consumer lag p95 spike", "commit rate slowdown"),
        verified_fixes=("scale consumer deployment", "reduce low-priority pipeline pressure"),
        rollback_procedure="restore previous replica count after lag recovers",
        recurrence_count=2,
        last_seen="2026-06-15",
        incident_links=("incident://2026-consumer-lag-spike-001",),
    ),
)

_STATIC_KEDB_INDEX = {record.root_cause_id: record for record in STATIC_KEDB_RECORDS}


def get_static_kedb_record(root_cause_id: str) -> KedbRecord | None:
    return _STATIC_KEDB_INDEX.get(root_cause_id)


def list_static_kedb_records() -> tuple[KedbRecord, ...]:
    return STATIC_KEDB_RECORDS
