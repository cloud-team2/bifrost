# Catalog — Root Causes (§8)

> FastAPI Agent 카탈로그 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **카탈로그**: [failure-types](./catalog-failure-types.md) · [incident→rootcause](./catalog-incident-root-cause-map.md) · [root-causes](./catalog-root-causes.md) · [evidence-matrix](./catalog-evidence-matrix.md) · [correlation-rules](./catalog-correlation-rules.md) · [runbooks](./catalog-remediation-runbooks.md) · [policy-matrix](./catalog-policy-matrix.md)

## 8. Catalog: Root Cause


### 1. 목적

RCA Agent는 이 문서의 root cause id 중 하나만 선택한다. catalog에 없는 원인은 생성하지 않고 `UNKNOWN_WITH_EVIDENCE_GAP`으로 보고한다.

각 root cause는 [§9 Evidence Matrix](catalog-evidence-matrix.md#9-catalog-evidence-matrix)의 필수 evidence 기준을 만족해야 확정 후보가 될 수 있다. 단, `UNKNOWN_WITH_EVIDENCE_GAP`, `MULTIPLE_POSSIBLE_CAUSES`, `CUSTOMER_OWNED_ROOT_CAUSE_LIKELY`는 확정 원인이 아니라 보류/에스컬레이션 상태이므로 별도 evidence matrix heading을 두지 않는다.

### 2. 공통 필드

| 필드 | 설명 |
| --- | --- |
| `root_cause_id` | 안정적인 원인 식별자 |
| `layer` | source, pipeline, kafka, sink, infra, change, data_quality |
| `owned_by` | bifrost, customer, shared |
| `direct_action_allowed` | Agent가 직접 조치 후보를 만들 수 있는지 |
| `default_confidence_cap` | 필수 evidence가 없을 때 confidence 상한 |

### 3. Source

| Root cause id | 설명 | 소유 | 직접 조치 |
| --- | --- | --- | --- |
| `SOURCE_DB_CONNECTION_TIMEOUT` | source DB 또는 dependency 연결 timeout이 pipeline extract 실패를 유발 | customer/shared | no |
| `SOURCE_AUTH_EXPIRED` | source credential/token 만료 또는 권한 부족 | customer/shared | no |
| `SOURCE_READ_LATENCY` | source read latency 증가가 pipeline 지연을 유발 | customer/shared | no |
| `SOURCE_DATA_NOT_READY` | source 데이터가 아직 생성되지 않았거나 watermark가 정체 | customer | no |
| `SOURCE_NETWORK_REACHABILITY` | Bifrost에서 source endpoint까지 network reachability 저하 | shared | limited |

Source 계층의 root cause는 대부분 고객사 소유 또는 shared ownership이다. Agent는 직접 DB 튜닝이나 SQL 실행을 하지 않는다.

### 4. Pipeline / Connector

| Root cause id | 설명 | 소유 | 직접 조치 |
| --- | --- | --- | --- |
| `CONNECTOR_TASK_FAILED` | connector task가 FAILED 상태로 전환 | bifrost | approval |
| `CONNECTOR_WORKER_REBALANCE_LOOP` | Kafka Connect worker rebalance가 반복되어 task 안정성이 낮음 | bifrost | approval |
| `PIPELINE_TASK_RETRY_EXHAUSTED` | pipeline task가 retry를 모두 소진 | bifrost | limited |
| `PIPELINE_CONFIG_INVALID` | pipeline 또는 connector 설정 오류 | bifrost/shared | change_management |
| `SCHEMA_MISMATCH` | schema 호환성 또는 serialization/deserialization 문제 | shared | change_management |

### 5. Kafka

| Root cause id | 설명 | 소유 | 직접 조치 |
| --- | --- | --- | --- |
| `CONSUMER_LAG_SPIKE` | consumer 처리량이 유입량보다 낮아 lag 증가 | bifrost | approval |
| `BROKER_RESOURCE_PRESSURE` | broker CPU, disk, network, request latency 압박 | bifrost/platform | approval |
| `PARTITION_IMBALANCE` | partition 또는 leader 분산이 불균형 | bifrost/platform | approval |
| `TOPIC_INGRESS_SPIKE` | topic 유입량 급증으로 downstream 처리 지연 | shared | limited |
| `CONSUMER_REBALANCE_LOOP` | consumer group rebalance 반복 | bifrost | approval |

### 6. Sink

| Root cause id | 설명 | 소유 | 직접 조치 |
| --- | --- | --- | --- |
| `SINK_DB_CONNECTION_TIMEOUT` | sink DB 또는 dependency 연결 timeout이 write 실패를 유발 | customer/shared | no |
| `SINK_AUTH_EXPIRED` | sink credential/token 만료 또는 권한 부족 | customer/shared | no |
| `SINK_WRITE_LATENCY` | sink write latency 증가로 connector/task 지연 | customer/shared | limited |
| `SINK_CONSTRAINT_VIOLATION` | sink constraint, duplicate key, schema 불일치 | shared | change_management |

Sink 계층 root cause도 고객사 소유 영역이 많다. Agent는 connector pause/resume이나 retry 완화 같은 Bifrost 소유 조치만 제안할 수 있다.

### 7. Kubernetes / Infra

| Root cause id | 설명 | 소유 | 직접 조치 |
| --- | --- | --- | --- |
| `POD_OOM_KILLED` | container memory limit 초과 | bifrost | approval |
| `POD_CRASH_LOOP` | application 또는 config 문제로 pod 반복 재시작 | bifrost | limited |
| `NODE_PRESSURE` | node resource pressure로 scheduling/eviction 발생 | platform | escalation |
| `PVC_PRESSURE` | volume 사용량 또는 I/O pressure | platform | escalation |
| `DEPLOYMENT_REGRESSION` | 신규 배포 이후 error/latency 악화 | bifrost | change_management |

### 8. Change

| Root cause id | 설명 | 소유 | 직접 조치 |
| --- | --- | --- | --- |
| `RECENT_CONFIG_CHANGE_REGRESSION` | config 변경 이후 장애 | bifrost/shared | change_management |
| `RECENT_SCHEMA_CHANGE_REGRESSION` | schema 변경 이후 장애 | shared | change_management |
| `RECENT_IMAGE_DEPLOYMENT_REGRESSION` | image 배포 이후 장애 | bifrost | change_management |
| `CREDENTIAL_ROTATION_REGRESSION` | credential rotate 이후 auth failure | shared | escalation |

### 9. Data Quality

| Root cause id | 설명 | 소유 | 직접 조치 |
| --- | --- | --- | --- |
| `UPSTREAM_DATA_VOLUME_ANOMALY` | source volume 급감/급증 | customer/shared | no |
| `PIPELINE_DUPLICATE_SPIKE` | pipeline 처리 중 중복 증가 | bifrost/shared | change_management |
| `PIPELINE_FRESHNESS_DELAY` | end-to-end freshness 지연 | shared | limited |
| `SCHEMA_NULL_RATE_SPIKE` | 특정 필드 null rate 증가 | customer/shared | no |

### 10. Unknown

| Root cause id | 설명 | 처리 |
| --- | --- | --- |
| `UNKNOWN_WITH_EVIDENCE_GAP` | catalog 후보를 확정할 만큼 evidence가 부족 | 추가 Retrieval 또는 escalation |
| `MULTIPLE_POSSIBLE_CAUSES` | 여러 후보가 비슷한 confidence를 가짐 | 추가 evidence 수집 |
| `CUSTOMER_OWNED_ROOT_CAUSE_LIKELY` | 고객사 소유 영역 가능성이 높음 | 근거 포함 escalation |

### 11. Versioning

Root cause id는 report, replay test, approval/audit record에 남으므로 함부로 바꾸지 않는다. 이름 변경이 필요하면 alias 기간을 둔다.

---

