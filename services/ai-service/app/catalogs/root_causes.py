"""Root Cause catalog (§8) for RCA candidate validation."""
from __future__ import annotations

from app.catalogs.types import CatalogLayer, DirectActionPolicy, RootCause

ROOT_CAUSES: tuple[RootCause, ...] = (
    RootCause(
        root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT',
        layer='source',
        description='source DB 또는 dependency 연결 timeout이 pipeline extract 실패를 유발',
        owned_by='customer/shared',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='SOURCE_AUTH_EXPIRED',
        layer='source',
        description='source credential/token 만료 또는 권한 부족',
        owned_by='customer/shared',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='SOURCE_READ_LATENCY',
        layer='source',
        description='source read latency 증가가 pipeline 지연을 유발',
        owned_by='customer/shared',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='SOURCE_DATA_NOT_READY',
        layer='source',
        description='source 데이터가 아직 생성되지 않았거나 watermark가 정체',
        owned_by='customer',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='SOURCE_NETWORK_REACHABILITY',
        layer='source',
        description='Bifrost에서 source endpoint까지 network reachability 저하',
        owned_by='shared',
        direct_action_allowed='limited',
    ),
    RootCause(
        root_cause_id='CONNECTOR_TASK_FAILED',
        layer='pipeline',
        description='connector task가 FAILED 상태로 전환',
        owned_by='bifrost',
        direct_action_allowed='approval',
    ),
    RootCause(
        root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP',
        layer='pipeline',
        description='Kafka Connect worker rebalance가 반복되어 task 안정성이 낮음',
        owned_by='bifrost',
        direct_action_allowed='approval',
    ),
    RootCause(
        root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED',
        layer='pipeline',
        description='pipeline task가 retry를 모두 소진',
        owned_by='bifrost',
        direct_action_allowed='limited',
    ),
    RootCause(
        root_cause_id='PIPELINE_CONFIG_INVALID',
        layer='pipeline',
        description='pipeline 또는 connector 설정 오류',
        owned_by='bifrost/shared',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='SCHEMA_MISMATCH',
        layer='pipeline',
        description='schema 호환성 또는 serialization/deserialization 문제',
        owned_by='shared',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='CONSUMER_LAG_SPIKE',
        layer='kafka',
        description='consumer 처리량이 유입량보다 낮아 lag 증가',
        owned_by='bifrost',
        direct_action_allowed='approval',
    ),
    RootCause(
        root_cause_id='BROKER_RESOURCE_PRESSURE',
        layer='kafka',
        description='broker CPU, disk, network, request latency 압박',
        owned_by='bifrost/platform',
        direct_action_allowed='approval',
    ),
    RootCause(
        root_cause_id='PARTITION_IMBALANCE',
        layer='kafka',
        description='partition 또는 leader 분산이 불균형',
        owned_by='bifrost/platform',
        direct_action_allowed='approval',
    ),
    RootCause(
        root_cause_id='TOPIC_INGRESS_SPIKE',
        layer='kafka',
        description='topic 유입량 급증으로 downstream 처리 지연',
        owned_by='shared',
        direct_action_allowed='limited',
    ),
    RootCause(
        root_cause_id='CONSUMER_REBALANCE_LOOP',
        layer='kafka',
        description='consumer group rebalance 반복',
        owned_by='bifrost',
        direct_action_allowed='approval',
    ),
    RootCause(
        root_cause_id='SINK_DB_CONNECTION_TIMEOUT',
        layer='sink',
        description='sink DB 또는 dependency 연결 timeout이 write 실패를 유발',
        owned_by='customer/shared',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='SINK_AUTH_EXPIRED',
        layer='sink',
        description='sink credential/token 만료 또는 권한 부족',
        owned_by='customer/shared',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='SINK_WRITE_LATENCY',
        layer='sink',
        description='sink write latency 증가로 connector/task 지연',
        owned_by='customer/shared',
        direct_action_allowed='limited',
    ),
    RootCause(
        root_cause_id='SINK_CONSTRAINT_VIOLATION',
        layer='sink',
        description='sink constraint, duplicate key, schema 불일치',
        owned_by='shared',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='POD_OOM_KILLED',
        layer='infra',
        description='container memory limit 초과',
        owned_by='bifrost',
        direct_action_allowed='approval',
    ),
    RootCause(
        root_cause_id='POD_CRASH_LOOP',
        layer='infra',
        description='application 또는 config 문제로 pod 반복 재시작',
        owned_by='bifrost',
        direct_action_allowed='limited',
    ),
    RootCause(
        root_cause_id='NODE_PRESSURE',
        layer='infra',
        description='node resource pressure로 scheduling/eviction 발생',
        owned_by='platform',
        direct_action_allowed='escalation',
    ),
    RootCause(
        root_cause_id='PVC_PRESSURE',
        layer='infra',
        description='volume 사용량 또는 I/O pressure',
        owned_by='platform',
        direct_action_allowed='escalation',
    ),
    RootCause(
        root_cause_id='DEPLOYMENT_REGRESSION',
        layer='infra',
        description='신규 배포 이후 error/latency 악화',
        owned_by='bifrost',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION',
        layer='change',
        description='config 변경 이후 장애',
        owned_by='bifrost/shared',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION',
        layer='change',
        description='schema 변경 이후 장애',
        owned_by='shared',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION',
        layer='change',
        description='image 배포 이후 장애',
        owned_by='bifrost',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='CREDENTIAL_ROTATION_REGRESSION',
        layer='change',
        description='credential rotate 이후 auth failure',
        owned_by='shared',
        direct_action_allowed='escalation',
    ),
    RootCause(
        root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY',
        layer='data_quality',
        description='source volume 급감/급증',
        owned_by='customer/shared',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='PIPELINE_DUPLICATE_SPIKE',
        layer='data_quality',
        description='pipeline 처리 중 중복 증가',
        owned_by='bifrost/shared',
        direct_action_allowed='change_management',
    ),
    RootCause(
        root_cause_id='PIPELINE_FRESHNESS_DELAY',
        layer='data_quality',
        description='end-to-end freshness 지연',
        owned_by='shared',
        direct_action_allowed='limited',
    ),
    RootCause(
        root_cause_id='SCHEMA_NULL_RATE_SPIKE',
        layer='data_quality',
        description='특정 필드 null rate 증가',
        owned_by='customer/shared',
        direct_action_allowed='no',
    ),
    RootCause(
        root_cause_id='UNKNOWN_WITH_EVIDENCE_GAP',
        layer='unknown',
        description='catalog 후보를 확정할 만큼 evidence가 부족',
        owned_by='unknown',
        direct_action_allowed='escalation',
    ),
    RootCause(
        root_cause_id='MULTIPLE_POSSIBLE_CAUSES',
        layer='unknown',
        description='여러 후보가 비슷한 confidence를 가짐',
        owned_by='unknown',
        direct_action_allowed='limited',
    ),
    RootCause(
        root_cause_id='CUSTOMER_OWNED_ROOT_CAUSE_LIKELY',
        layer='unknown',
        description='고객사 소유 영역 가능성이 높음',
        owned_by='unknown',
        direct_action_allowed='escalation',
    ),
)

ROOT_CAUSE_INDEX: dict[str, RootCause] = {item.root_cause_id: item for item in ROOT_CAUSES}


def list_root_causes(
    layer: CatalogLayer | str | None = None,
    *,
    direct_action_allowed: DirectActionPolicy | str | None = None,
) -> tuple[RootCause, ...]:
    """Return root causes, optionally filtered by layer and direct-action policy."""
    items = ROOT_CAUSES
    if layer is not None:
        items = tuple(item for item in items if item.layer == layer)
    if direct_action_allowed is not None:
        items = tuple(item for item in items if item.direct_action_allowed == direct_action_allowed)
    return items


def get_root_cause(root_cause_id: str) -> RootCause | None:
    return ROOT_CAUSE_INDEX.get(root_cause_id)


def is_known_root_cause(root_cause_id: str) -> bool:
    return root_cause_id in ROOT_CAUSE_INDEX


def root_cause_ids(layer: CatalogLayer | str | None = None) -> tuple[str, ...]:
    return tuple(item.root_cause_id for item in list_root_causes(layer))
