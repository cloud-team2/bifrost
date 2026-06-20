"""Evidence Matrix catalog (§9) for RCA and Verifier evidence checks."""
from __future__ import annotations

from collections.abc import Iterable

from app.catalogs.types import EvidenceKind, EvidenceProfile, EvidenceRule

EVIDENCE_PROFILES: tuple[EvidenceProfile, ...] = (
    EvidenceProfile(
        root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT',
        required=(
            EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='required', evidence='source connection timeout 증가', example='`pipeline_source_connection_timeout_total` 증가'),
            EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='required', evidence='pipeline extract/read 단계 timeout log', example='`extract_users` task `ConnectionTimeout`'),
            EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='required', evidence='pipeline read latency 증가', example='extract duration p95 증가'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='sink write 단계 정상', example='sink write latency 정상'),
            EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='최근 source credential rotate 없음', example='auth 변경 없음'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='negative', evidence='sink write timeout 증가', example='source 단독 원인 가능성 낮춤'),
            EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='negative', evidence='source metric 정상', example='source timeout 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SOURCE_AUTH_EXPIRED',
        required=(
            EvidenceRule(root_cause_id='SOURCE_AUTH_EXPIRED', kind='required', evidence='auth/permission error log', example='`AccessDenied`, `token expired`, 인증 실패, 권한 거부, 비밀번호 인증 실패'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SOURCE_AUTH_EXPIRED', kind='supporting', evidence='credential rotation 또는 secret 변경 이력', example='rotate 직후 실패'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SOURCE_AUTH_EXPIRED', kind='negative', evidence='connection timeout만 존재하고 auth error 없음', example='auth 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SOURCE_READ_LATENCY',
        required=(
            EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='required', evidence='source read latency 증가', example='p95 read latency 증가'),
            EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='required', evidence='extract task duration 증가', example='task runtime 증가'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='supporting', evidence='downstream 처리 정상', example='Kafka/sink 지표 정상'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='negative', evidence='source connection failure', example='latency가 아니라 connectivity 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SOURCE_DATA_NOT_READY',
        required=(
            EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='required', evidence='source watermark 정체 또는 expected partition 미생성', example='source watermark가 SLA 시간 이상 갱신되지 않음'),
            EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='required', evidence='pipeline extract 결과 empty batch 반복', example='row count 0 또는 기준 대비 급감'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='supporting', evidence='upstream schedule 지연 또는 source 생성 job 지연', example='source owner schedule delay'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='negative', evidence='source read timeout 또는 auth failure', example='데이터 미준비보다 연결/auth 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SOURCE_NETWORK_REACHABILITY',
        required=(
            EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='required', evidence='Bifrost에서 source endpoint reachability 실패', example='connection refused, no route to host, 연결 실패, 호스트 연결 실패, 네트워크 도달 실패'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='supporting', evidence='여러 pipeline에서 같은 source endpoint 연결 실패', example='shared dependency timeout'),
            EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='supporting', evidence='고객사 source 내부 지표 정상이나 network path error 존재', example='network error code 증가'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='negative', evidence='auth error 또는 query error만 존재', example='network 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='CONNECTOR_TASK_FAILED',
        required=(
            EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='required', evidence='connector task status `FAILED`', example='Kafka Connect task 상태', semantic_allowed=False),
            EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='required', evidence='task trace 또는 worker log', example='소스 커넥터 오류'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='supporting', evidence='최근 connector config/schema 변경', example='변경 이후 실패'),
        ),
        negative=(
            EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='negative', evidence='worker 전체 장애', example='worker/root infra 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED',
        required=(
            EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='required', evidence='retry count exhausted', example='max retry reached'),
            EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='required', evidence='동일 task 반복 실패', example='retry history'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='supporting', evidence='transient dependency error', example='source/sink timeout'),
        ),
        negative=(
            EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='negative', evidence='첫 실패이며 retry 여지 있음', example='exhausted 아님'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SCHEMA_MISMATCH',
        required=(
            EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='required', evidence='serialization/deserialization/schema error', example='incompatible schema, deserialization error, 스키마 불일치, 스키마 오류, 역직렬화 오류'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='supporting', evidence='schema version 변경 이력', example='recent subject version'),
            EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='supporting', evidence='데이터 샘플 구조 변화', example='field type mismatch'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='negative', evidence='schema 변경 없음', example='후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP',
        required=(
            EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='required', evidence='Connect worker rebalance 이벤트 반복', example='rebalance count 급증'),
            EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='required', evidence='task assignment가 반복적으로 변경', example='task revoked/assigned loop'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='supporting', evidence='worker pod restart 또는 network flap', example='worker instability'),
        ),
        negative=(
            EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='negative', evidence='단일 connector task exception만 존재', example='task 자체 실패 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='PIPELINE_CONFIG_INVALID',
        required=(
            EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='required', evidence='config validation error 또는 invalid option log', example='unknown config, invalid converter'),
            EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='required', evidence='최근 pipeline/connector config 변경', example='config diff 존재', causality_type='temporal', temporality_required=True, causal_chain_step=1),
        ),
        supporting=(
            EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='supporting', evidence='rollback 또는 이전 config에서 정상 동작', example='config regression evidence'),
        ),
        negative=(
            EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='negative', evidence='변경 없이 dependency timeout만 존재', example='config 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='CONSUMER_LAG_SPIKE',
        required=(
            EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='required', evidence='consumer lag 급증', example='lag p95 증가', semantic_allowed=False),
            EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='required', evidence='offset progression 둔화', example='commit rate 감소', semantic_allowed=False),
        ),
        supporting=(
            EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='supporting', evidence='topic ingress 증가', example='incoming messages 증가'),
            EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='supporting', evidence='consumer pod resource pressure', example='CPU/memory saturation'),
        ),
        negative=(
            EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='negative', evidence='broker 장애 evidence', example='broker 원인 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='BROKER_RESOURCE_PRESSURE',
        required=(
            EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='required', evidence='broker resource saturation', example='disk, CPU, network'),
            EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='required', evidence='broker request latency 증가', example='produce/fetch latency'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='supporting', evidence='under-replicated partition 증가', example='ISR 변화'),
        ),
        negative=(
            EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='negative', evidence='consumer만 느림', example='consumer 원인 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='PARTITION_IMBALANCE',
        required=(
            EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='required', evidence='broker별 partition 또는 leader skew', example='distribution imbalance'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='supporting', evidence='특정 broker만 resource pressure', example='broker hot spot'),
            EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='supporting', evidence='Cruise Control proposal 개선 예상', example='rebalance proposal'),
        ),
        negative=(
            EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='negative', evidence='균등 분산 상태', example='후보 배제'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='TOPIC_INGRESS_SPIKE',
        required=(
            EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='required', evidence='topic ingress rate 급증', example='messages in/sec 또는 bytes in/sec 증가'),
            EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='required', evidence='upstream volume 증가와 시간 상관', example='source row count 급증', causality_type='temporal', temporality_required=True, causal_chain_step=1),
        ),
        supporting=(
            EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='supporting', evidence='consumer lag가 ingress 증가 직후 동반', example='lag start time correlation', causality_type='temporal', temporality_required=True, causal_chain_step=2),
        ),
        negative=(
            EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='negative', evidence='ingress 정상인데 consumer 처리량만 감소', example='consumer/sink 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='CONSUMER_REBALANCE_LOOP',
        required=(
            EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='required', evidence='consumer group rebalance 반복', example='rebalance event count 증가'),
            EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='required', evidence='member join/leave 반복 또는 assignment churn', example='member id 변경 반복'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='supporting', evidence='pod restart 또는 heartbeat/session timeout', example='consumer stability issue'),
        ),
        negative=(
            EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='negative', evidence='lag 증가만 있고 rebalance 없음', example='lag spike 원인으로 부족'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SINK_DB_CONNECTION_TIMEOUT',
        required=(
            EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='required', evidence='sink write timeout 증가', example='sink connector write timeout'),
            EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='required', evidence='sink dependency connection error', example='reachability or pool error'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='source read 정상', example='upstream 정상'),
            EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='sink write latency 증가', example='write duration p95 증가'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='negative', evidence='source extract timeout', example='source 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SINK_WRITE_LATENCY',
        required=(
            EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='required', evidence='sink write latency 증가', example='write p95 증가'),
            EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='required', evidence='connector sink task 처리시간 증가', example='flush/batch duration 증가'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='supporting', evidence='source/Kafka 정상', example='upstream 정상'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='negative', evidence='sink auth error', example='auth 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SINK_AUTH_EXPIRED',
        required=(
            EvidenceRule(root_cause_id='SINK_AUTH_EXPIRED', kind='required', evidence='sink auth/permission error log', example='`AccessDenied`, `token expired`, 인증 실패, 권한 거부, 비밀번호 인증 실패'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SINK_AUTH_EXPIRED', kind='supporting', evidence='credential rotation 또는 secret 변경 이력', example='rotate 직후 실패'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SINK_AUTH_EXPIRED', kind='negative', evidence='connection timeout만 존재하고 auth error 없음', example='auth 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SINK_CONSTRAINT_VIOLATION',
        required=(
            EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='required', evidence='sink constraint 또는 duplicate key error', example='unique constraint violation'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='supporting', evidence='schema 또는 transform 변경 이력', example='field 변경 후 write error'),
            EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='supporting', evidence='동일 record 반복 실패', example='poison record 가능성'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='negative', evidence='sink timeout/latency만 존재', example='constraint 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='POD_OOM_KILLED',
        required=(
            EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='required', evidence='pod last state OOMKilled', example='Kubernetes status', semantic_allowed=False),
            EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='required', evidence='restart count 증가', example='restart count delta', semantic_allowed=False),
        ),
        supporting=(
            EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='supporting', evidence='memory usage limit 근접', example='container memory metric'),
        ),
        negative=(
            EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='negative', evidence='app-level source timeout만 존재', example='source 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='DEPLOYMENT_REGRESSION',
        required=(
            EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='required', evidence='배포 이후 error/latency 증가', example='rollout time correlation', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
            EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='required', evidence='image/config diff', example='change record', semantic_allowed=False),
        ),
        supporting=(
            EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='supporting', evidence='rollback 후 개선', example='after evidence'),
        ),
        negative=(
            EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='negative', evidence='배포 전부터 문제 지속', example='변경 원인 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='POD_CRASH_LOOP',
        required=(
            EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='required', evidence='pod `CrashLoopBackOff` 또는 반복 restart', example='restart count 증가'),
            EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='required', evidence='container termination reason과 app error summary', example='exit code, startup failure'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='supporting', evidence='최근 config/image 변경', example='rollout 직후 crash'),
        ),
        negative=(
            EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='negative', evidence='정상 pod 상태에서 app-level timeout만 존재', example='pod crash 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='NODE_PRESSURE',
        required=(
            EvidenceRule(root_cause_id='NODE_PRESSURE', kind='required', evidence='node condition pressure', example='MemoryPressure, DiskPressure, PIDPressure'),
            EvidenceRule(root_cause_id='NODE_PRESSURE', kind='required', evidence='affected pod scheduling/eviction event', example='eviction 또는 pending 증가'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='NODE_PRESSURE', kind='supporting', evidence='같은 node의 여러 workload 영향', example='node-local symptom'),
        ),
        negative=(
            EvidenceRule(root_cause_id='NODE_PRESSURE', kind='negative', evidence='특정 app pod만 실패', example='app/config 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='PVC_PRESSURE',
        required=(
            EvidenceRule(root_cause_id='PVC_PRESSURE', kind='required', evidence='PVC 사용량 또는 I/O latency 임계치 초과', example='volume usage high, fsync latency'),
            EvidenceRule(root_cause_id='PVC_PRESSURE', kind='required', evidence='pod log에 disk full 또는 write failure', example='no space left on device'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='PVC_PRESSURE', kind='supporting', evidence='broker 또는 DB workload와 시간 상관', example='storage-backed workload 영향'),
        ),
        negative=(
            EvidenceRule(root_cause_id='PVC_PRESSURE', kind='negative', evidence='CPU/memory pressure만 존재', example='PVC 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION',
        required=(
            EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='required', evidence='config 변경 시점 이후 error/latency 증가', example='change time correlation', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
            EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='required', evidence='변경 diff와 증상 계층이 연결됨', example='connector task config diff', semantic_allowed=False, causality_type='causal', causal_chain_step=2),
        ),
        supporting=(
            EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='supporting', evidence='rollback 또는 이전 config로 개선', example='after evidence'),
        ),
        negative=(
            EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='negative', evidence='변경 전부터 동일 증상 지속', example='config regression 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION',
        required=(
            EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='required', evidence='schema version 변경 이후 schema/serialization error 증가', example='subject version change', semantic_allowed=False),
            EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='required', evidence='compatibility check 실패 또는 필드 타입 변화', example='incompatible schema', semantic_allowed=False),
        ),
        supporting=(
            EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='supporting', evidence='affected topic/connector가 변경 subject를 사용', example='topology match'),
        ),
        negative=(
            EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='negative', evidence='schema 변경 없음', example='schema regression 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION',
        required=(
            EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='required', evidence='image rollout 이후 error/latency/restart 증가', example='deployment rollout event', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
            EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='required', evidence='이전 image 대비 config/runtime 차이', example='image tag diff', semantic_allowed=False),
        ),
        supporting=(
            EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='supporting', evidence='rollback 후 개선', example='after evidence'),
        ),
        negative=(
            EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='negative', evidence='rollout 전부터 문제 지속', example='image regression 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='CREDENTIAL_ROTATION_REGRESSION',
        required=(
            EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='required', evidence='credential rotation 이후 auth failure 증가', example='rotate time correlation', semantic_allowed=False),
            EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='required', evidence='affected dependency가 해당 credential을 사용', example='dependency ownership match', semantic_allowed=False),
        ),
        supporting=(
            EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='supporting', evidence='rotation audit 또는 secret version 변경', example='version diff'),
        ),
        negative=(
            EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='negative', evidence='auth error 없이 timeout만 존재', example='credential 후보 약화'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY',
        required=(
            EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='required', evidence='source row count 또는 topic ingress가 기준 대비 급변', example='volume z-score 이상'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='supporting', evidence='upstream schedule/change와 시간 상관', example='upstream batch size change'),
            EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='supporting', evidence='pipeline 처리량 저하 없이 입력만 변동', example='downstream 정상'),
        ),
        negative=(
            EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='negative', evidence='pipeline failure 때문에 output만 감소', example='pipeline 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='PIPELINE_DUPLICATE_SPIKE',
        required=(
            EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='required', evidence='duplicate count 또는 duplicate key error 증가', example='duplicate metric 증가'),
            EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='required', evidence='retry/replay/backfill 또는 idempotency gap', example='repeated processing evidence'),
        ),
        supporting=(
            EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='supporting', evidence='최근 transform/config 변경', example='key derivation change'),
        ),
        negative=(
            EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='negative', evidence='upstream부터 duplicate가 존재', example='upstream data quality 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='PIPELINE_FRESHNESS_DELAY',
        required=(
            EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='required', evidence='end-to-end freshness 또는 watermark delay 증가', example='freshness SLA breach', semantic_allowed=False),
            EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='required', evidence='pipeline stage 중 병목 단계 식별', example='source/Kafka/sink stage duration', semantic_allowed=False),
        ),
        supporting=(
            EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='supporting', evidence='lag 또는 sink latency 동반', example='downstream bottleneck'),
        ),
        negative=(
            EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='negative', evidence='source 데이터 미생성만 확인', example='source data not ready 후보 우선'),
        ),
    ),
    EvidenceProfile(
        root_cause_id='SCHEMA_NULL_RATE_SPIKE',
        required=(
            EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='required', evidence='특정 field null rate 급증', example='null rate metric 증가', semantic_allowed=False),
            EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='required', evidence='schema/source/transform 변경과 시간 상관', example='field mapping change', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
        ),
        supporting=(
            EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='supporting', evidence='downstream validation error 동반', example='bad record increase'),
        ),
        negative=(
            EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='negative', evidence='전체 volume 급감만 존재', example='volume anomaly 후보 우선'),
        ),
    ),
)

EVIDENCE_RULES: tuple[EvidenceRule, ...] = (
    EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='required', evidence='source connection timeout 증가', example='`pipeline_source_connection_timeout_total` 증가'),
    EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='required', evidence='pipeline extract/read 단계 timeout log', example='`extract_users` task `ConnectionTimeout`'),
    EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='required', evidence='pipeline read latency 증가', example='extract duration p95 증가'),
    EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='sink write 단계 정상', example='sink write latency 정상'),
    EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='최근 source credential rotate 없음', example='auth 변경 없음'),
    EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='negative', evidence='sink write timeout 증가', example='source 단독 원인 가능성 낮춤'),
    EvidenceRule(root_cause_id='SOURCE_DB_CONNECTION_TIMEOUT', kind='negative', evidence='source metric 정상', example='source timeout 후보 약화'),
    EvidenceRule(root_cause_id='SOURCE_AUTH_EXPIRED', kind='required', evidence='auth/permission error log', example='`AccessDenied`, `token expired`, 인증 실패, 권한 거부, 비밀번호 인증 실패'),
    EvidenceRule(root_cause_id='SOURCE_AUTH_EXPIRED', kind='supporting', evidence='credential rotation 또는 secret 변경 이력', example='rotate 직후 실패'),
    EvidenceRule(root_cause_id='SOURCE_AUTH_EXPIRED', kind='negative', evidence='connection timeout만 존재하고 auth error 없음', example='auth 후보 약화'),
    EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='required', evidence='source read latency 증가', example='p95 read latency 증가'),
    EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='required', evidence='extract task duration 증가', example='task runtime 증가'),
    EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='supporting', evidence='downstream 처리 정상', example='Kafka/sink 지표 정상'),
    EvidenceRule(root_cause_id='SOURCE_READ_LATENCY', kind='negative', evidence='source connection failure', example='latency가 아니라 connectivity 후보 우선'),
    EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='required', evidence='source watermark 정체 또는 expected partition 미생성', example='source watermark가 SLA 시간 이상 갱신되지 않음'),
    EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='required', evidence='pipeline extract 결과 empty batch 반복', example='row count 0 또는 기준 대비 급감'),
    EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='supporting', evidence='upstream schedule 지연 또는 source 생성 job 지연', example='source owner schedule delay'),
    EvidenceRule(root_cause_id='SOURCE_DATA_NOT_READY', kind='negative', evidence='source read timeout 또는 auth failure', example='데이터 미준비보다 연결/auth 후보 우선'),
    EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='required', evidence='Bifrost에서 source endpoint reachability 실패', example='connection refused, no route to host, 연결 실패, 호스트 연결 실패, 네트워크 도달 실패'),
    EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='supporting', evidence='여러 pipeline에서 같은 source endpoint 연결 실패', example='shared dependency timeout'),
    EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='supporting', evidence='고객사 source 내부 지표 정상이나 network path error 존재', example='network error code 증가'),
    EvidenceRule(root_cause_id='SOURCE_NETWORK_REACHABILITY', kind='negative', evidence='auth error 또는 query error만 존재', example='network 후보 약화'),
    EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='required', evidence='connector task status `FAILED`', example='Kafka Connect task 상태', semantic_allowed=False),
    EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='required', evidence='task trace 또는 worker log', example='exception stack summary'),
    EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='supporting', evidence='최근 connector config/schema 변경', example='변경 이후 실패'),
    EvidenceRule(root_cause_id='CONNECTOR_TASK_FAILED', kind='negative', evidence='worker 전체 장애', example='worker/root infra 후보 우선'),
    EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='required', evidence='retry count exhausted', example='max retry reached'),
    EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='required', evidence='동일 task 반복 실패', example='retry history'),
    EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='supporting', evidence='transient dependency error', example='source/sink timeout'),
    EvidenceRule(root_cause_id='PIPELINE_TASK_RETRY_EXHAUSTED', kind='negative', evidence='첫 실패이며 retry 여지 있음', example='exhausted 아님'),
    EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='required', evidence='serialization/deserialization/schema error', example='incompatible schema, deserialization error, 스키마 불일치, 스키마 오류, 역직렬화 오류'),
    EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='supporting', evidence='schema version 변경 이력', example='recent subject version'),
    EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='supporting', evidence='데이터 샘플 구조 변화', example='field type mismatch'),
    EvidenceRule(root_cause_id='SCHEMA_MISMATCH', kind='negative', evidence='schema 변경 없음', example='후보 약화'),
    EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='required', evidence='Connect worker rebalance 이벤트 반복', example='rebalance count 급증'),
    EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='required', evidence='task assignment가 반복적으로 변경', example='task revoked/assigned loop'),
    EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='supporting', evidence='worker pod restart 또는 network flap', example='worker instability'),
    EvidenceRule(root_cause_id='CONNECTOR_WORKER_REBALANCE_LOOP', kind='negative', evidence='단일 connector task exception만 존재', example='task 자체 실패 후보 우선'),
    EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='required', evidence='config validation error 또는 invalid option log', example='unknown config, invalid converter'),
    EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='required', evidence='최근 pipeline/connector config 변경', example='config diff 존재'),
    EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='supporting', evidence='rollback 또는 이전 config에서 정상 동작', example='config regression evidence'),
    EvidenceRule(root_cause_id='PIPELINE_CONFIG_INVALID', kind='negative', evidence='변경 없이 dependency timeout만 존재', example='config 후보 약화'),
    EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='required', evidence='consumer lag 급증', example='lag p95 증가', semantic_allowed=False),
    EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='required', evidence='offset progression 둔화', example='commit rate 감소', semantic_allowed=False),
    EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='supporting', evidence='topic ingress 증가', example='incoming messages 증가'),
    EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='supporting', evidence='consumer pod resource pressure', example='CPU/memory saturation'),
    EvidenceRule(root_cause_id='CONSUMER_LAG_SPIKE', kind='negative', evidence='broker 장애 evidence', example='broker 원인 우선'),
    EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='required', evidence='broker resource saturation', example='disk, CPU, network'),
    EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='required', evidence='broker request latency 증가', example='produce/fetch latency'),
    EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='supporting', evidence='under-replicated partition 증가', example='ISR 변화'),
    EvidenceRule(root_cause_id='BROKER_RESOURCE_PRESSURE', kind='negative', evidence='consumer만 느림', example='consumer 원인 우선'),
    EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='required', evidence='broker별 partition 또는 leader skew', example='distribution imbalance'),
    EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='supporting', evidence='특정 broker만 resource pressure', example='broker hot spot'),
    EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='supporting', evidence='Cruise Control proposal 개선 예상', example='rebalance proposal'),
    EvidenceRule(root_cause_id='PARTITION_IMBALANCE', kind='negative', evidence='균등 분산 상태', example='후보 배제'),
    EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='required', evidence='topic ingress rate 급증', example='messages in/sec 또는 bytes in/sec 증가'),
    EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='required', evidence='upstream volume 증가와 시간 상관', example='source row count 급증'),
    EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='supporting', evidence='consumer lag가 ingress 증가 직후 동반', example='lag start time correlation'),
    EvidenceRule(root_cause_id='TOPIC_INGRESS_SPIKE', kind='negative', evidence='ingress 정상인데 consumer 처리량만 감소', example='consumer/sink 후보 우선'),
    EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='required', evidence='consumer group rebalance 반복', example='rebalance event count 증가'),
    EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='required', evidence='member join/leave 반복 또는 assignment churn', example='member id 변경 반복'),
    EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='supporting', evidence='pod restart 또는 heartbeat/session timeout', example='consumer stability issue'),
    EvidenceRule(root_cause_id='CONSUMER_REBALANCE_LOOP', kind='negative', evidence='lag 증가만 있고 rebalance 없음', example='lag spike 원인으로 부족'),
    EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='required', evidence='sink write timeout 증가', example='sink connector write timeout'),
    EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='required', evidence='sink dependency connection error', example='reachability or pool error'),
    EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='source read 정상', example='upstream 정상'),
    EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='supporting', evidence='sink write latency 증가', example='write duration p95 증가'),
    EvidenceRule(root_cause_id='SINK_DB_CONNECTION_TIMEOUT', kind='negative', evidence='source extract timeout', example='source 후보 우선'),
    EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='required', evidence='sink write latency 증가', example='write p95 증가'),
    EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='required', evidence='connector sink task 처리시간 증가', example='flush/batch duration 증가'),
    EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='supporting', evidence='source/Kafka 정상', example='upstream 정상'),
    EvidenceRule(root_cause_id='SINK_WRITE_LATENCY', kind='negative', evidence='sink auth error', example='auth 후보 우선'),
    EvidenceRule(root_cause_id='SINK_AUTH_EXPIRED', kind='required', evidence='sink auth/permission error log', example='`AccessDenied`, `token expired`, 인증 실패, 권한 거부, 비밀번호 인증 실패'),
    EvidenceRule(root_cause_id='SINK_AUTH_EXPIRED', kind='supporting', evidence='credential rotation 또는 secret 변경 이력', example='rotate 직후 실패'),
    EvidenceRule(root_cause_id='SINK_AUTH_EXPIRED', kind='negative', evidence='connection timeout만 존재하고 auth error 없음', example='auth 후보 약화'),
    EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='required', evidence='sink constraint 또는 duplicate key error', example='unique constraint violation'),
    EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='supporting', evidence='schema 또는 transform 변경 이력', example='field 변경 후 write error'),
    EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='supporting', evidence='동일 record 반복 실패', example='poison record 가능성'),
    EvidenceRule(root_cause_id='SINK_CONSTRAINT_VIOLATION', kind='negative', evidence='sink timeout/latency만 존재', example='constraint 후보 약화'),
    EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='required', evidence='pod last state OOMKilled', example='Kubernetes status', semantic_allowed=False),
    EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='required', evidence='restart count 증가', example='restart count delta', semantic_allowed=False),
    EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='supporting', evidence='memory usage limit 근접', example='container memory metric'),
    EvidenceRule(root_cause_id='POD_OOM_KILLED', kind='negative', evidence='app-level source timeout만 존재', example='source 후보 우선'),
    EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='required', evidence='배포 이후 error/latency 증가', example='rollout time correlation', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
    EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='required', evidence='image/config diff', example='change record', semantic_allowed=False),
    EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='supporting', evidence='rollback 후 개선', example='after evidence'),
    EvidenceRule(root_cause_id='DEPLOYMENT_REGRESSION', kind='negative', evidence='배포 전부터 문제 지속', example='변경 원인 약화'),
    EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='required', evidence='pod `CrashLoopBackOff` 또는 반복 restart', example='restart count 증가'),
    EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='required', evidence='container termination reason과 app error summary', example='exit code, startup failure'),
    EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='supporting', evidence='최근 config/image 변경', example='rollout 직후 crash'),
    EvidenceRule(root_cause_id='POD_CRASH_LOOP', kind='negative', evidence='정상 pod 상태에서 app-level timeout만 존재', example='pod crash 후보 약화'),
    EvidenceRule(root_cause_id='NODE_PRESSURE', kind='required', evidence='node condition pressure', example='MemoryPressure, DiskPressure, PIDPressure'),
    EvidenceRule(root_cause_id='NODE_PRESSURE', kind='required', evidence='affected pod scheduling/eviction event', example='eviction 또는 pending 증가'),
    EvidenceRule(root_cause_id='NODE_PRESSURE', kind='supporting', evidence='같은 node의 여러 workload 영향', example='node-local symptom'),
    EvidenceRule(root_cause_id='NODE_PRESSURE', kind='negative', evidence='특정 app pod만 실패', example='app/config 후보 우선'),
    EvidenceRule(root_cause_id='PVC_PRESSURE', kind='required', evidence='PVC 사용량 또는 I/O latency 임계치 초과', example='volume usage high, fsync latency'),
    EvidenceRule(root_cause_id='PVC_PRESSURE', kind='required', evidence='pod log에 disk full 또는 write failure', example='no space left on device'),
    EvidenceRule(root_cause_id='PVC_PRESSURE', kind='supporting', evidence='broker 또는 DB workload와 시간 상관', example='storage-backed workload 영향', causality_type='correlational', temporality_required=True, causal_chain_step=2),
    EvidenceRule(root_cause_id='PVC_PRESSURE', kind='negative', evidence='CPU/memory pressure만 존재', example='PVC 후보 약화'),
    EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='required', evidence='config 변경 시점 이후 error/latency 증가', example='change time correlation', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
    EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='required', evidence='변경 diff와 증상 계층이 연결됨', example='connector task config diff', semantic_allowed=False, causality_type='causal', causal_chain_step=2),
    EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='supporting', evidence='rollback 또는 이전 config로 개선', example='after evidence'),
    EvidenceRule(root_cause_id='RECENT_CONFIG_CHANGE_REGRESSION', kind='negative', evidence='변경 전부터 동일 증상 지속', example='config regression 후보 약화'),
    EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='required', evidence='schema version 변경 이후 schema/serialization error 증가', example='subject version change', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
    EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='required', evidence='compatibility check 실패 또는 필드 타입 변화', example='incompatible schema', semantic_allowed=False),
    EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='supporting', evidence='affected topic/connector가 변경 subject를 사용', example='topology match'),
    EvidenceRule(root_cause_id='RECENT_SCHEMA_CHANGE_REGRESSION', kind='negative', evidence='schema 변경 없음', example='schema regression 후보 약화'),
    EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='required', evidence='image rollout 이후 error/latency/restart 증가', example='deployment rollout event', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
    EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='required', evidence='이전 image 대비 config/runtime 차이', example='image tag diff', semantic_allowed=False),
    EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='supporting', evidence='rollback 후 개선', example='after evidence'),
    EvidenceRule(root_cause_id='RECENT_IMAGE_DEPLOYMENT_REGRESSION', kind='negative', evidence='rollout 전부터 문제 지속', example='image regression 후보 약화'),
    EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='required', evidence='credential rotation 이후 auth failure 증가', example='rotate time correlation', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
    EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='required', evidence='affected dependency가 해당 credential을 사용', example='dependency ownership match', semantic_allowed=False),
    EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='supporting', evidence='rotation audit 또는 secret version 변경', example='version diff'),
    EvidenceRule(root_cause_id='CREDENTIAL_ROTATION_REGRESSION', kind='negative', evidence='auth error 없이 timeout만 존재', example='credential 후보 약화'),
    EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='required', evidence='source row count 또는 topic ingress가 기준 대비 급변', example='volume z-score 이상'),
    EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='supporting', evidence='upstream schedule/change와 시간 상관', example='upstream batch size change', causality_type='correlational', temporality_required=True, causal_chain_step=1),
    EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='supporting', evidence='pipeline 처리량 저하 없이 입력만 변동', example='downstream 정상'),
    EvidenceRule(root_cause_id='UPSTREAM_DATA_VOLUME_ANOMALY', kind='negative', evidence='pipeline failure 때문에 output만 감소', example='pipeline 후보 우선'),
    EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='required', evidence='duplicate count 또는 duplicate key error 증가', example='duplicate metric 증가'),
    EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='required', evidence='retry/replay/backfill 또는 idempotency gap', example='repeated processing evidence'),
    EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='supporting', evidence='최근 transform/config 변경', example='key derivation change'),
    EvidenceRule(root_cause_id='PIPELINE_DUPLICATE_SPIKE', kind='negative', evidence='upstream부터 duplicate가 존재', example='upstream data quality 후보 우선'),
    EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='required', evidence='end-to-end freshness 또는 watermark delay 증가', example='freshness SLA breach', semantic_allowed=False),
    EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='required', evidence='pipeline stage 중 병목 단계 식별', example='source/Kafka/sink stage duration', semantic_allowed=False),
    EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='supporting', evidence='lag 또는 sink latency 동반', example='downstream bottleneck'),
    EvidenceRule(root_cause_id='PIPELINE_FRESHNESS_DELAY', kind='negative', evidence='source 데이터 미생성만 확인', example='source data not ready 후보 우선'),
    EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='required', evidence='특정 field null rate 급증', example='null rate metric 증가', semantic_allowed=False),
    EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='required', evidence='schema/source/transform 변경과 시간 상관', example='field mapping change', semantic_allowed=False, causality_type='temporal', temporality_required=True, causal_chain_step=1),
    EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='supporting', evidence='downstream validation error 동반', example='bad record increase'),
    EvidenceRule(root_cause_id='SCHEMA_NULL_RATE_SPIKE', kind='negative', evidence='전체 volume 급감만 존재', example='volume anomaly 후보 우선'),
)

EVIDENCE_PROFILE_INDEX: dict[str, EvidenceProfile] = {
    item.root_cause_id: item for item in EVIDENCE_PROFILES
}


def get_evidence_profile(root_cause_id: str) -> EvidenceProfile | None:
    return EVIDENCE_PROFILE_INDEX.get(root_cause_id)


def list_evidence_profiles() -> tuple[EvidenceProfile, ...]:
    return EVIDENCE_PROFILES


def get_evidence(root_cause_id: str, kind: EvidenceKind | str) -> tuple[EvidenceRule, ...]:
    profile = get_evidence_profile(root_cause_id)
    if profile is None:
        return ()
    if kind == "required":
        return profile.required
    if kind == "supporting":
        return profile.supporting
    if kind == "negative":
        return profile.negative
    if kind == "exclusion":
        return profile.exclusion
    return ()


def get_required_evidence(root_cause_id: str) -> tuple[EvidenceRule, ...]:
    return get_evidence(root_cause_id, "required")


def get_supporting_evidence(root_cause_id: str) -> tuple[EvidenceRule, ...]:
    return get_evidence(root_cause_id, "supporting")


def get_negative_evidence(root_cause_id: str) -> tuple[EvidenceRule, ...]:
    return get_evidence(root_cause_id, "negative")


def get_missing_required_evidence(
    root_cause_id: str,
    observed_evidence: Iterable[str],
) -> tuple[EvidenceRule, ...]:
    """Return required rules whose evidence text is absent from observed evidence labels."""
    observed = set(observed_evidence)
    return tuple(rule for rule in get_required_evidence(root_cause_id) if rule.evidence not in observed)


def required_evidence_satisfied(root_cause_id: str, observed_evidence: Iterable[str]) -> bool:
    return not get_missing_required_evidence(root_cause_id, observed_evidence)
