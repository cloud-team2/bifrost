"""Failure Types catalog (§6) for observed incident classification."""
from __future__ import annotations

from app.catalogs.types import CatalogLayer, FailureType

FAILURE_TYPES: tuple[FailureType, ...] = (
    FailureType(
        incident_type='SOURCE_CONNECTION_TIMEOUT',
        layer='source',
        description='source dependency 연결 지연 또는 timeout',
        signals=('read timeout', 'connection timeout', 'reachability failure'),
    ),
    FailureType(
        incident_type='SOURCE_AUTH_FAILURE',
        layer='source',
        description='source credential, 권한, token 문제',
        signals=('source auth error', 'read path authentication failure', 'expired token', 'permission error', 'expired secret'),
    ),
    FailureType(
        incident_type='SOURCE_READ_LATENCY',
        layer='source',
        description='source read 단계 지연',
        signals=('extract duration 증가', 'source read latency 증가', 'read duration p95 상승', 'scan volume 증가'),
    ),
    FailureType(
        incident_type='SOURCE_DATA_NOT_AVAILABLE',
        layer='source',
        description='source에서 기대 데이터가 생성되지 않음',
        signals=('empty batch', 'watermark 정체'),
    ),
    FailureType(
        incident_type='PIPELINE_TASK_FAILED',
        layer='pipeline',
        description='pipeline task 또는 job 실패',
        signals=('task failed', 'retry exhausted', 'retry budget exhausted', 'retry limit reached'),
    ),
    FailureType(
        incident_type='CONNECTOR_TASK_FAILED',
        layer='pipeline',
        description='Kafka Connect connector task 실패',
        signals=('terminal task failure state', 'connector trace error'),
    ),
    FailureType(
        incident_type='CONNECTOR_WORKER_UNHEALTHY',
        layer='pipeline',
        description='Connect worker 자체 상태 이상',
        signals=('worker unavailable', 'rebalance loop'),
    ),
    FailureType(
        incident_type='PIPELINE_RETRY_BACKOFF',
        layer='pipeline',
        description='retry/backoff로 처리 지연',
        signals=('retry count 증가', 'backoff duration 증가'),
    ),
    FailureType(
        incident_type='SCHEMA_MISMATCH',
        layer='pipeline',
        description='schema 호환성 또는 serialization 문제',
        signals=('schema incompatible', 'deserialization error'),
    ),
    FailureType(
        incident_type='CONSUMER_LAG_SPIKE',
        layer='kafka',
        description='consumer group lag 급증',
        signals=('lag p95 증가', 'offset progression 정체'),
    ),
    FailureType(
        incident_type='TOPIC_INGRESS_SPIKE',
        layer='kafka',
        description='topic 유입량 급증',
        signals=('messages in/sec 증가',),
    ),
    FailureType(
        incident_type='BROKER_RESOURCE_PRESSURE',
        layer='kafka',
        description='broker CPU, disk, network 압박',
        signals=('disk usage', 'request latency', 'ISR 변화'),
    ),
    FailureType(
        incident_type='PARTITION_IMBALANCE',
        layer='kafka',
        description='partition 또는 broker 부하 불균형',
        signals=('broker별 partition skew',),
    ),
    FailureType(
        incident_type='REBALANCE_LOOP',
        layer='kafka',
        description='consumer group 또는 Connect rebalance 반복',
        signals=('rebalance count 증가',),
    ),
    FailureType(
        incident_type='SINK_CONNECTION_TIMEOUT',
        layer='sink',
        description='sink dependency 연결 지연 또는 timeout',
        signals=('write timeout', 'connection timeout'),
    ),
    FailureType(
        incident_type='SINK_AUTH_FAILURE',
        layer='sink',
        description='sink credential 또는 권한 문제',
        signals=('sink auth error', 'write path permission denied', 'permission error', 'expired secret'),
    ),
    FailureType(
        incident_type='SINK_WRITE_LATENCY',
        layer='sink',
        description='sink write 단계 지연',
        signals=('write latency', 'batch duration 증가'),
    ),
    FailureType(
        incident_type='SINK_CONSTRAINT_ERROR',
        layer='sink',
        description='sink schema/constraint/write validation 문제',
        signals=('duplicate key', 'constraint violation'),
    ),
    FailureType(
        incident_type='POD_OOM_KILLED',
        layer='infra',
        description='memory 초과로 pod 종료',
        signals=('OOMKilled', 'restart count 증가'),
    ),
    FailureType(
        incident_type='POD_CRASH_LOOP',
        layer='infra',
        description='반복 재시작',
        signals=('CrashLoopBackOff',),
    ),
    FailureType(
        incident_type='NODE_PRESSURE',
        layer='infra',
        description='node resource pressure',
        signals=('DiskPressure', 'MemoryPressure'),
    ),
    FailureType(
        incident_type='DEPLOYMENT_ROLLOUT_REGRESSION',
        layer='infra',
        description='배포 이후 상태 악화',
        signals=('rollout event 이후 error 증가', 'image rollout followed by error increase', 'deployment followed by failure'),
    ),
    FailureType(
        incident_type='PVC_PRESSURE',
        layer='infra',
        description='persistent volume 사용량 또는 I/O 문제',
        signals=('disk full', 'high I/O latency'),
    ),
    FailureType(
        incident_type='CONFIG_CHANGE_REGRESSION',
        layer='change',
        description='설정 변경 이후 장애',
        signals=('config diff와 시간 상관', 'configuration change followed by error increase'),
    ),
    FailureType(
        incident_type='SCHEMA_CHANGE_REGRESSION',
        layer='change',
        description='schema 변경 이후 장애',
        signals=('schema version 변경 후 error', 'schema subject change followed by serialization error'),
    ),
    FailureType(
        incident_type='IMAGE_DEPLOYMENT_REGRESSION',
        layer='change',
        description='image 배포 이후 장애',
        signals=('new image rollout 이후 failure', 'image rollout followed by runtime error increase', 'runtime image rollout followed by restart'),
    ),
    FailureType(
        incident_type='CREDENTIAL_ROTATION_FAILURE',
        layer='change',
        description='credential rotate 이후 장애',
        signals=('auth failure와 rotate time 상관',),
    ),
    FailureType(
        incident_type='FRESHNESS_DELAY',
        layer='data_quality',
        description='데이터 최신성 지연',
        signals=('watermark delay',),
    ),
    FailureType(
        incident_type='VOLUME_ANOMALY',
        layer='data_quality',
        description='데이터량 이상',
        signals=('batch row count 급감/급증',),
    ),
    FailureType(
        incident_type='DUPLICATE_SPIKE',
        layer='data_quality',
        description='중복 증가',
        signals=('duplicate count 증가', 'record duplication increase', 'idempotency policy gap', 'replay/backfill window'),
    ),
    FailureType(
        incident_type='NULL_RATE_SPIKE',
        layer='data_quality',
        description='null rate 증가',
        signals=('column null rate 증가',),
    ),
    FailureType(
        incident_type='UNKNOWN_NEEDS_MORE_EVIDENCE',
        layer='unknown',
        description='필수 evidence가 부족하거나 catalog에 없는 증상',
        signals=(),
    ),
    FailureType(
        incident_type='CUSTOMER_OWNED_ESCALATION',
        layer='unknown',
        description='고객사 소유 영역으로 판단되지만 증거 정리가 필요한 경우',
        signals=(),
    ),
)

FAILURE_TYPE_INDEX: dict[str, FailureType] = {item.incident_type: item for item in FAILURE_TYPES}


def list_failure_types(layer: CatalogLayer | str | None = None) -> tuple[FailureType, ...]:
    """Return all failure types, optionally filtered by catalog layer."""
    if layer is None:
        return FAILURE_TYPES
    return tuple(item for item in FAILURE_TYPES if item.layer == layer)


def get_failure_type(incident_type: str) -> FailureType | None:
    """Look up a failure type by stable incident_type id."""
    return FAILURE_TYPE_INDEX.get(incident_type)


def is_known_incident_type(incident_type: str) -> bool:
    return incident_type in FAILURE_TYPE_INDEX


def incident_type_ids(layer: CatalogLayer | str | None = None) -> tuple[str, ...]:
    return tuple(item.incident_type for item in list_failure_types(layer))
