"""Remediation Runbooks catalog (§11) for root-cause action candidates."""
from __future__ import annotations

from app.catalogs.incident_rootcause_map import get_root_cause_candidates
from app.catalogs.types import Runbook, RunbookActionTemplate, RunbookDisposition

# Backward-compatible public alias used by older callers.
RunbookAction = RunbookActionTemplate

ROOT_CAUSE_RUNBOOKS: tuple[Runbook, ...] = (
    Runbook(
        root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'escalation', 'runtime_tool'),
        basis='고객사 source 수정은 금지하고, 근거 수집·전달·조건부 connector task 재시작만 허용',
        actions=(
            RunbookActionTemplate(action_name='collect_source_timeout_evidence', action_type='workflow_action', risk='read_only', description='source timeout log/metric 추가 수집', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='escalate_to_customer_owner', action_type='escalation', risk='low', description='고객사 담당자에게 evidence 전달', policy='allow', tool_name=None),
            RunbookActionTemplate(action_name='pause_non_critical_pipeline', action_type='workflow_action', risk='high', description='downstream 압박 완화를 위해 비긴급 pipeline 일시 중지', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='restart_connector_task', action_type='runtime_tool', risk='high', description='timeout이 transient이고 task가 failed일 때 재시작', policy='approval', tool_name='restart_connector'),
        ),
        forbidden_actions=('source DB connection limit 직접 변경', 'source DB query 실행', '고객사 DB restart'),
    ),
    Runbook(
        root_cause_id='SOURCE_AUTH_EXPIRED',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'escalation', 'runtime_tool'),
        basis='secret 원문 조회·임의 변경 금지',
        actions=(
            RunbookActionTemplate(action_name='collect_auth_error_evidence', action_type='workflow_action', risk='read_only', description='auth error와 변경 이력 수집', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='escalate_credential_rotation', action_type='escalation', risk='low', description='credential owner에게 갱신 요청', policy='allow', tool_name=None),
            RunbookActionTemplate(action_name='pause_pipeline', action_type='workflow_action', risk='high', description='반복 실패로 downstream noise가 클 때 일시 중지', policy='approval', tool_name=None),
        ),
        forbidden_actions=('secret 원문 조회', 'credential 임의 변경'),
    ),
    Runbook(
        root_cause_id='SOURCE_READ_LATENCY',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='source 내부 read 성능은 고객사/공유 영역',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='SOURCE_DATA_NOT_READY',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='upstream 데이터 생성 지연은 고객사 영역',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='SOURCE_NETWORK_REACHABILITY',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='네트워크 경로 evidence를 정리해 customer/platform owner에게 전달',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='CONNECTOR_TASK_FAILED',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'runtime_tool'),
        basis='Bifrost 소유 connector task 조치 가능',
        actions=(
            RunbookActionTemplate(action_name='restart_connector_task', action_type='runtime_tool', risk='high', description='실패 task 재시작', policy='approval', tool_name='restart_connector'),
            RunbookActionTemplate(action_name='pause_connector', action_type='runtime_tool', risk='high', description='반복 실패로 영향이 커질 때 일시 중지', policy='approval', tool_name='pause_connector'),
            RunbookActionTemplate(action_name='resume_connector', action_type='runtime_tool', risk='high', description='원인 해소 후 재개', policy='approval', tool_name='resume_connector'),
            RunbookActionTemplate(action_name='collect_connector_trace', action_type='workflow_action', risk='read_only', description='task trace와 worker log 수집', policy='read_only', tool_name=None),
        ),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='worker 안정성 조치는 별도 runbook 확정 전 자동 실행 금지',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED',
        disposition='diagnose_only',
        allowed_action_types=(),
        basis='실패 이력과 원인 후보를 보고하고 추가 root cause로 좁힌 뒤 조치',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='PIPELINE_CONFIG_INVALID',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='config 변경은 변경관리 절차 필요',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='SCHEMA_MISMATCH',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'runtime_tool'),
        basis='데이터 확산 차단·rollback은 정책에 따라 처리',
        actions=(
            RunbookActionTemplate(action_name='collect_schema_changes', action_type='workflow_action', risk='read_only', description='subject version과 compatibility 확인', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='pause_pipeline', action_type='workflow_action', risk='high', description='잘못된 데이터 확산 방지', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='rollback_pipeline', action_type='workflow_action', risk='medium', description='schema 변경 또는 배포 rollback', policy='change_management', tool_name=None),
        ),
        forbidden_actions=('schema compatibility 강제 변경', 'sink table 임의 변경'),
    ),
    Runbook(
        root_cause_id='CONSUMER_LAG_SPIKE',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'composite_action', 'runtime_tool'),
        basis='lag 원인 구분 후 허용된 조치만 제안',
        actions=(
            RunbookActionTemplate(action_name='get_consumer_lag', action_type='runtime_tool', risk='read_only', description='lag 상세 확인', policy='read_only', tool_name='get_consumer_lag'),
            RunbookActionTemplate(action_name='scale_consumer_deployment', action_type='workflow_action', risk='high', description='consumer replica 증가', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='create_rebalance_proposal', action_type='workflow_action', risk='high', description='broker imbalance가 동반될 때 proposal 생성', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='pause_low_priority_pipeline', action_type='composite_action', risk='high', description='중요도가 낮은 pipeline 일시 중지', policy='approval', tool_name=None),
        ),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='BROKER_RESOURCE_PRESSURE',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'runtime_tool', 'escalation'),
        basis='rebalance proposal 또는 platform escalation',
        actions=(
            RunbookActionTemplate(action_name='collect_broker_metrics', action_type='workflow_action', risk='read_only', description='broker별 CPU/disk/network 확인', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='create_rebalance_proposal', action_type='workflow_action', risk='high', description='Cruise Control proposal 생성', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='approve_rebalance', action_type='workflow_action', risk='high', description='proposal 승인', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='escalate_platform_capacity', action_type='escalation', risk='low', description='capacity 부족 시 platform team escalation', policy='allow', tool_name=None),
        ),
        forbidden_actions=('broker 강제 재시작', 'topic delete'),
    ),
    Runbook(
        root_cause_id='PARTITION_IMBALANCE',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='rebalance 상세 runbook 확정 전 자동 실행 금지',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='TOPIC_INGRESS_SPIKE',
        disposition='diagnose_only',
        allowed_action_types=(),
        basis='유입 증가 원인을 보고하고 downstream 보호 조치는 다른 root cause에서 판단',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='CONSUMER_REBALANCE_LOOP',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='consumer group 안정성 조치는 수동 검토 필요',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='SINK_DB_CONNECTION_TIMEOUT',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'runtime_tool', 'escalation'),
        basis='sink 보호를 위한 connector pause/resume과 owner 전달',
        actions=(
            RunbookActionTemplate(action_name='collect_sink_timeout_evidence', action_type='workflow_action', risk='read_only', description='sink write timeout과 latency 수집', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='pause_connector', action_type='runtime_tool', risk='high', description='sink 보호를 위해 write 중단', policy='approval', tool_name='pause_connector'),
            RunbookActionTemplate(action_name='resume_connector', action_type='runtime_tool', risk='high', description='sink 회복 후 재개', policy='approval', tool_name='resume_connector'),
            RunbookActionTemplate(action_name='escalate_to_customer_owner', action_type='escalation', risk='low', description='sink owner에게 evidence 전달', policy='allow', tool_name=None),
        ),
        forbidden_actions=('sink DB connection limit 변경', 'sink DB restart', '임의 SQL 실행'),
    ),
    Runbook(
        root_cause_id='SINK_AUTH_EXPIRED',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='credential owner에게 전달, secret 임의 변경 금지',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='SINK_WRITE_LATENCY',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'composite_action', 'runtime_tool'),
        basis='sink 보호·pressure 완화 후보 제안',
        actions=(
            RunbookActionTemplate(action_name='reduce_pipeline_pressure', action_type='composite_action', risk='high', description='비긴급 pipeline 일시 중지 또는 rate 완화', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='pause_connector', action_type='runtime_tool', risk='high', description='sink 보호', policy='approval', tool_name='pause_connector'),
            RunbookActionTemplate(action_name='collect_sink_write_metrics', action_type='workflow_action', risk='read_only', description='write latency와 retry/backoff 수집', policy='read_only', tool_name=None),
        ),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='SINK_CONSTRAINT_VIOLATION',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='데이터 정합성 영향으로 자동 조치 금지',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='POD_OOM_KILLED',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'runtime_tool'),
        basis='pod 상태·메모리 확인 후 정책상 가능한 조치만 제안',
        actions=(
            RunbookActionTemplate(action_name='collect_pod_status', action_type='workflow_action', risk='read_only', description='pod status와 restart count 확인', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='collect_memory_metrics', action_type='workflow_action', risk='read_only', description='memory usage와 limit 확인', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='scale_consumer_deployment', action_type='workflow_action', risk='high', description='처리 병렬화로 pod pressure 완화', policy='approval', tool_name=None),
            RunbookActionTemplate(action_name='rollback_pipeline', action_type='workflow_action', risk='medium', description='배포 이후 OOM이면 rollback', policy='change_management', tool_name=None),
        ),
        forbidden_actions=('pod exec', 'container 내부 파일 수정'),
    ),
    Runbook(
        root_cause_id='POD_CRASH_LOOP',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='app/config 원인 확인 전 자동 restart loop 금지',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='NODE_PRESSURE',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='node/cluster는 platform 영역',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='PVC_PRESSURE',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='storage 증설·정리는 platform 변경관리 영역',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='DEPLOYMENT_REGRESSION',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'runtime_tool'),
        basis='최근 배포 회귀에 대한 pause/rollback 후보',
        actions=(
            RunbookActionTemplate(action_name='collect_recent_changes', action_type='workflow_action', risk='read_only', description='rollout, image, config diff 확인', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='rollback_pipeline', action_type='workflow_action', risk='medium', description='문제 배포 rollback', policy='change_management', tool_name=None),
            RunbookActionTemplate(action_name='pause_pipeline', action_type='workflow_action', risk='high', description='영향 확산 차단', policy='approval', tool_name=None),
        ),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='config rollback은 변경관리 절차 필요',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='schema rollback/수정은 변경관리 절차 필요',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='image rollback은 상세 change 검토 필요',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='CREDENTIAL_ROTATION_REGRESSION',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='credential owner에게 rotation evidence 전달',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='source volume 변화는 customer/shared 영역',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='PIPELINE_DUPLICATE_SPIKE',
        disposition='manual_change_required',
        allowed_action_types=('notification', 'escalation'),
        basis='replay/idempotency 이슈는 데이터 정합성 영향',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='PIPELINE_FRESHNESS_DELAY',
        disposition='diagnose_only',
        allowed_action_types=(),
        basis='병목 root cause로 추가 분류 후 조치',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='SCHEMA_NULL_RATE_SPIKE',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='source/schema owner에게 evidence 전달',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='UNKNOWN_WITH_EVIDENCE_GAP',
        disposition='detailed_runbook',
        allowed_action_types=('workflow_action', 'escalation'),
        basis='추가 근거 수집 또는 운영자 전달',
        actions=(
            RunbookActionTemplate(action_name='collect_additional_evidence', action_type='workflow_action', risk='read_only', description='Planner가 추가 evidence 계획', policy='read_only', tool_name=None),
            RunbookActionTemplate(action_name='escalate_to_operator', action_type='escalation', risk='low', description='확정 불가 상태로 운영자에게 전달', policy='allow', tool_name=None),
        ),
        forbidden_actions=('mutation action 생성',),
    ),
    Runbook(
        root_cause_id='MULTIPLE_POSSIBLE_CAUSES',
        disposition='diagnose_only',
        allowed_action_types=(),
        basis='추가 evidence로 후보를 좁히기 전 조치 금지',
        actions=(),
        forbidden_actions=(),
    ),
    Runbook(
        root_cause_id='CUSTOMER_OWNED_ROOT_CAUSE_LIKELY',
        disposition='escalation_only',
        allowed_action_types=('escalation',),
        basis='고객사 소유 가능성이 높으므로 evidence summary 전달',
        actions=(),
        forbidden_actions=(),
    ),
)

RUNBOOK_INDEX: dict[str, Runbook] = {item.root_cause_id: item for item in ROOT_CAUSE_RUNBOOKS}


def list_runbooks(disposition: RunbookDisposition | str | None = None) -> tuple[Runbook, ...]:
    if disposition is None:
        return ROOT_CAUSE_RUNBOOKS
    return tuple(item for item in ROOT_CAUSE_RUNBOOKS if item.disposition == disposition)


def get_runbook(identifier: str) -> Runbook | None:
    """Look up a runbook by root_cause_id, or the first mapped runbook for an incident_type."""
    if identifier in RUNBOOK_INDEX:
        return RUNBOOK_INDEX[identifier]
    incident_runbooks = get_runbooks_for_incident(identifier)
    return incident_runbooks[0] if incident_runbooks else None


def get_runbook_for_root_cause(root_cause_id: str) -> Runbook | None:
    return RUNBOOK_INDEX.get(root_cause_id)


def get_actions_for_root_cause(root_cause_id: str) -> tuple[RunbookActionTemplate, ...]:
    runbook = get_runbook_for_root_cause(root_cause_id)
    return runbook.actions if runbook else ()


def get_runbooks_for_incident(incident_type: str) -> tuple[Runbook, ...]:
    """Return runbooks for every mapped root-cause candidate of an incident type."""
    return tuple(
        runbook
        for root_cause_id in get_root_cause_candidates(incident_type)
        if (runbook := get_runbook(root_cause_id)) is not None
    )


def get_actions_for_incident(incident_type: str) -> list[RunbookActionTemplate]:
    """Return action templates reachable from an incident type via §7 mapping."""
    return [action for runbook in get_runbooks_for_incident(incident_type) for action in runbook.actions]


def incident_types_with_runbooks() -> tuple[str, ...]:
    from app.catalogs.incident_rootcause_map import incident_type_ids

    return tuple(
        incident_type
        for incident_type in incident_type_ids()
        if get_runbooks_for_incident(incident_type)
    )


def root_cause_ids_with_runbooks() -> tuple[str, ...]:
    return tuple(item.root_cause_id for item in ROOT_CAUSE_RUNBOOKS)


def has_runbook(root_cause_id: str) -> bool:
    return root_cause_id in RUNBOOK_INDEX
