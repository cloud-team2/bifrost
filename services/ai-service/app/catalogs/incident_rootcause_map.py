"""Incident→RootCause map catalog (§7) for Classifier to RCA handoff."""
from __future__ import annotations

from app.catalogs.types import IncidentRootCauseMapEntry

INCIDENT_ROOT_CAUSE_MAP: tuple[IncidentRootCauseMapEntry, ...] = (
    IncidentRootCauseMapEntry(
        incident_type='SOURCE_CONNECTION_TIMEOUT',
        root_cause_ids=('SOURCE_DB_CONNECTION_TIMEOUT', 'SOURCE_NETWORK_REACHABILITY'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SOURCE_AUTH_FAILURE',
        root_cause_ids=('SOURCE_AUTH_EXPIRED', 'CREDENTIAL_ROTATION_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SOURCE_READ_LATENCY',
        root_cause_ids=('SOURCE_READ_LATENCY', 'SOURCE_DB_CONNECTION_TIMEOUT'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SOURCE_DATA_NOT_AVAILABLE',
        root_cause_ids=('SOURCE_DATA_NOT_READY', 'UPSTREAM_DATA_VOLUME_ANOMALY'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='PIPELINE_TASK_FAILED',
        root_cause_ids=('PIPELINE_TASK_RETRY_EXHAUSTED', 'PIPELINE_CONFIG_INVALID', 'DEPLOYMENT_REGRESSION', 'RECENT_CONFIG_CHANGE_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='CONNECTOR_TASK_FAILED',
        root_cause_ids=('CONNECTOR_TASK_FAILED', 'SCHEMA_MISMATCH', 'PIPELINE_CONFIG_INVALID', 'SOURCE_DB_CONNECTION_TIMEOUT', 'SINK_DB_CONNECTION_TIMEOUT', 'SOURCE_NETWORK_REACHABILITY'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='CONNECTOR_WORKER_UNHEALTHY',
        root_cause_ids=('CONNECTOR_WORKER_REBALANCE_LOOP', 'POD_CRASH_LOOP', 'NODE_PRESSURE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='PIPELINE_RETRY_BACKOFF',
        root_cause_ids=('PIPELINE_TASK_RETRY_EXHAUSTED', 'SOURCE_DB_CONNECTION_TIMEOUT', 'SINK_DB_CONNECTION_TIMEOUT', 'BROKER_RESOURCE_PRESSURE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SCHEMA_MISMATCH',
        root_cause_ids=('SCHEMA_MISMATCH', 'RECENT_SCHEMA_CHANGE_REGRESSION', 'SINK_CONSTRAINT_VIOLATION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='CONSUMER_LAG_SPIKE',
        root_cause_ids=('CONSUMER_LAG_SPIKE', 'SINK_WRITE_LATENCY', 'BROKER_RESOURCE_PRESSURE', 'TOPIC_INGRESS_SPIKE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='TOPIC_INGRESS_SPIKE',
        root_cause_ids=('TOPIC_INGRESS_SPIKE', 'UPSTREAM_DATA_VOLUME_ANOMALY'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='BROKER_RESOURCE_PRESSURE',
        root_cause_ids=('BROKER_RESOURCE_PRESSURE', 'PARTITION_IMBALANCE', 'NODE_PRESSURE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='PARTITION_IMBALANCE',
        root_cause_ids=('PARTITION_IMBALANCE', 'BROKER_RESOURCE_PRESSURE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='REBALANCE_LOOP',
        root_cause_ids=('CONSUMER_REBALANCE_LOOP', 'CONNECTOR_WORKER_REBALANCE_LOOP'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SINK_CONNECTION_TIMEOUT',
        root_cause_ids=('SINK_DB_CONNECTION_TIMEOUT', 'SOURCE_DB_CONNECTION_TIMEOUT'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SINK_AUTH_FAILURE',
        root_cause_ids=('SINK_AUTH_EXPIRED', 'CREDENTIAL_ROTATION_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SINK_WRITE_LATENCY',
        root_cause_ids=('SINK_WRITE_LATENCY', 'CONSUMER_LAG_SPIKE', 'BROKER_RESOURCE_PRESSURE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SINK_CONSTRAINT_ERROR',
        root_cause_ids=('SINK_CONSTRAINT_VIOLATION', 'SCHEMA_MISMATCH', 'RECENT_SCHEMA_CHANGE_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='POD_OOM_KILLED',
        root_cause_ids=('POD_OOM_KILLED', 'RECENT_IMAGE_DEPLOYMENT_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='POD_CRASH_LOOP',
        root_cause_ids=('POD_CRASH_LOOP', 'PIPELINE_CONFIG_INVALID', 'RECENT_CONFIG_CHANGE_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='NODE_PRESSURE',
        root_cause_ids=('NODE_PRESSURE', 'BROKER_RESOURCE_PRESSURE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='DEPLOYMENT_ROLLOUT_REGRESSION',
        root_cause_ids=('DEPLOYMENT_REGRESSION', 'RECENT_IMAGE_DEPLOYMENT_REGRESSION', 'RECENT_CONFIG_CHANGE_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='PVC_PRESSURE',
        root_cause_ids=('PVC_PRESSURE', 'BROKER_RESOURCE_PRESSURE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='CONFIG_CHANGE_REGRESSION',
        root_cause_ids=('RECENT_CONFIG_CHANGE_REGRESSION', 'PIPELINE_CONFIG_INVALID'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='SCHEMA_CHANGE_REGRESSION',
        root_cause_ids=('RECENT_SCHEMA_CHANGE_REGRESSION', 'SCHEMA_MISMATCH', 'SINK_CONSTRAINT_VIOLATION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='IMAGE_DEPLOYMENT_REGRESSION',
        root_cause_ids=('RECENT_IMAGE_DEPLOYMENT_REGRESSION', 'DEPLOYMENT_REGRESSION', 'POD_CRASH_LOOP', 'POD_OOM_KILLED'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='CREDENTIAL_ROTATION_FAILURE',
        root_cause_ids=('CREDENTIAL_ROTATION_REGRESSION', 'SOURCE_AUTH_EXPIRED', 'SINK_AUTH_EXPIRED'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='FRESHNESS_DELAY',
        root_cause_ids=('PIPELINE_FRESHNESS_DELAY', 'SOURCE_DATA_NOT_READY', 'CONSUMER_LAG_SPIKE', 'SINK_WRITE_LATENCY'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='VOLUME_ANOMALY',
        root_cause_ids=('UPSTREAM_DATA_VOLUME_ANOMALY', 'SOURCE_DATA_NOT_READY', 'TOPIC_INGRESS_SPIKE'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='DUPLICATE_SPIKE',
        root_cause_ids=('PIPELINE_DUPLICATE_SPIKE', 'RECENT_CONFIG_CHANGE_REGRESSION'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='NULL_RATE_SPIKE',
        root_cause_ids=('SCHEMA_NULL_RATE_SPIKE', 'RECENT_SCHEMA_CHANGE_REGRESSION', 'UPSTREAM_DATA_VOLUME_ANOMALY'),
    ),
    IncidentRootCauseMapEntry(
        incident_type='UNKNOWN_NEEDS_MORE_EVIDENCE',
        root_cause_ids=('UNKNOWN_WITH_EVIDENCE_GAP',),
    ),
    IncidentRootCauseMapEntry(
        incident_type='CUSTOMER_OWNED_ESCALATION',
        root_cause_ids=('CUSTOMER_OWNED_ROOT_CAUSE_LIKELY',),
    ),
)

# Backward-compatible spelling for callers that use the filename terminology.
INCIDENT_ROOTCAUSE_MAP = INCIDENT_ROOT_CAUSE_MAP

INCIDENT_ROOT_CAUSE_INDEX: dict[str, IncidentRootCauseMapEntry] = {
    item.incident_type: item for item in INCIDENT_ROOT_CAUSE_MAP
}


def get_map_entry(incident_type: str) -> IncidentRootCauseMapEntry | None:
    return INCIDENT_ROOT_CAUSE_INDEX.get(incident_type)


def get_root_cause_candidates(incident_type: str) -> tuple[str, ...]:
    """Return prioritized root_cause_id candidates for an incident type."""
    entry = get_map_entry(incident_type)
    return entry.root_cause_ids if entry else ()


def has_mapping(incident_type: str) -> bool:
    return incident_type in INCIDENT_ROOT_CAUSE_INDEX


def incident_type_ids() -> tuple[str, ...]:
    return tuple(item.incident_type for item in INCIDENT_ROOT_CAUSE_MAP)
