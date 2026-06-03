# Catalog — Incident→RootCause Map (§7)

> FastAPI Agent 카탈로그 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **카탈로그**: [failure-types](./catalog-failure-types.md) · [incident→rootcause](./catalog-incident-root-cause-map.md) · [root-causes](./catalog-root-causes.md) · [evidence-matrix](./catalog-evidence-matrix.md) · [correlation-rules](./catalog-correlation-rules.md) · [runbooks](./catalog-remediation-runbooks.md) · [policy-matrix](./catalog-policy-matrix.md)

## 7. Catalog: Incident→RootCause Map


### 1. 목적

이 문서는 Classifier가 만든 `incident_type`이 RCA Agent의 `root_cause_id` 후보군으로 어떻게 이어지는지 정의한다.

`incident_type`은 관측된 증상 분류이고, `root_cause_id`는 evidence matrix로 검증할 원인 후보다. 이름이 같아 보이는 항목도 같은 개념으로 취급하지 않는다.

### 2. 매핑 원칙

1. Classifier는 하나 이상의 `incident_type`을 만들 수 있다.
2. RCA는 이 매핑의 후보군 안에서 root cause를 우선 검증한다.
3. 후보군 밖 root cause가 필요하면 Planner가 추가 evidence 수집 사유를 남긴다.
4. 모든 최종 root cause는 [§8 Root Cause Catalog](catalog-root-causes.md#8-catalog-root-cause)와 [§9 Evidence Matrix](catalog-evidence-matrix.md#9-catalog-evidence-matrix)를 만족해야 한다.
5. 확정 근거가 부족하면 `UNKNOWN_WITH_EVIDENCE_GAP`으로 남긴다.

### 3. Source

| Incident type | 우선 root cause 후보 |
| --- | --- |
| `SOURCE_CONNECTION_TIMEOUT` | `SOURCE_DB_CONNECTION_TIMEOUT`, `SOURCE_NETWORK_REACHABILITY` |
| `SOURCE_AUTH_FAILURE` | `SOURCE_AUTH_EXPIRED`, `CREDENTIAL_ROTATION_REGRESSION` |
| `SOURCE_READ_LATENCY` | `SOURCE_READ_LATENCY`, `SOURCE_DB_CONNECTION_TIMEOUT` |
| `SOURCE_DATA_NOT_AVAILABLE` | `SOURCE_DATA_NOT_READY`, `UPSTREAM_DATA_VOLUME_ANOMALY` |

### 4. Pipeline / Connector

| Incident type | 우선 root cause 후보 |
| --- | --- |
| `PIPELINE_TASK_FAILED` | `PIPELINE_TASK_RETRY_EXHAUSTED`, `PIPELINE_CONFIG_INVALID`, `DEPLOYMENT_REGRESSION`, `RECENT_CONFIG_CHANGE_REGRESSION` |
| `CONNECTOR_TASK_FAILED` | `CONNECTOR_TASK_FAILED`, `SCHEMA_MISMATCH`, `PIPELINE_CONFIG_INVALID`, `SOURCE_DB_CONNECTION_TIMEOUT`, `SINK_DB_CONNECTION_TIMEOUT` |
| `CONNECTOR_WORKER_UNHEALTHY` | `CONNECTOR_WORKER_REBALANCE_LOOP`, `POD_CRASH_LOOP`, `NODE_PRESSURE` |
| `PIPELINE_RETRY_BACKOFF` | `PIPELINE_TASK_RETRY_EXHAUSTED`, `SOURCE_DB_CONNECTION_TIMEOUT`, `SINK_DB_CONNECTION_TIMEOUT`, `BROKER_RESOURCE_PRESSURE` |
| `SCHEMA_MISMATCH` | `SCHEMA_MISMATCH`, `RECENT_SCHEMA_CHANGE_REGRESSION`, `SINK_CONSTRAINT_VIOLATION` |

### 5. Kafka / Streaming

| Incident type | 우선 root cause 후보 |
| --- | --- |
| `CONSUMER_LAG_SPIKE` | `CONSUMER_LAG_SPIKE`, `SINK_WRITE_LATENCY`, `BROKER_RESOURCE_PRESSURE`, `TOPIC_INGRESS_SPIKE` |
| `TOPIC_INGRESS_SPIKE` | `TOPIC_INGRESS_SPIKE`, `UPSTREAM_DATA_VOLUME_ANOMALY` |
| `BROKER_RESOURCE_PRESSURE` | `BROKER_RESOURCE_PRESSURE`, `PARTITION_IMBALANCE`, `NODE_PRESSURE` |
| `PARTITION_IMBALANCE` | `PARTITION_IMBALANCE`, `BROKER_RESOURCE_PRESSURE` |
| `REBALANCE_LOOP` | `CONSUMER_REBALANCE_LOOP`, `CONNECTOR_WORKER_REBALANCE_LOOP` |

### 6. Sink

| Incident type | 우선 root cause 후보 |
| --- | --- |
| `SINK_CONNECTION_TIMEOUT` | `SINK_DB_CONNECTION_TIMEOUT`, `SOURCE_DB_CONNECTION_TIMEOUT` |
| `SINK_AUTH_FAILURE` | `SINK_AUTH_EXPIRED`, `CREDENTIAL_ROTATION_REGRESSION` |
| `SINK_WRITE_LATENCY` | `SINK_WRITE_LATENCY`, `CONSUMER_LAG_SPIKE`, `BROKER_RESOURCE_PRESSURE` |
| `SINK_CONSTRAINT_ERROR` | `SINK_CONSTRAINT_VIOLATION`, `SCHEMA_MISMATCH`, `RECENT_SCHEMA_CHANGE_REGRESSION` |

### 7. Kubernetes / Infra

| Incident type | 우선 root cause 후보 |
| --- | --- |
| `POD_OOM_KILLED` | `POD_OOM_KILLED`, `RECENT_IMAGE_DEPLOYMENT_REGRESSION` |
| `POD_CRASH_LOOP` | `POD_CRASH_LOOP`, `PIPELINE_CONFIG_INVALID`, `RECENT_CONFIG_CHANGE_REGRESSION` |
| `NODE_PRESSURE` | `NODE_PRESSURE`, `BROKER_RESOURCE_PRESSURE` |
| `DEPLOYMENT_ROLLOUT_REGRESSION` | `DEPLOYMENT_REGRESSION`, `RECENT_IMAGE_DEPLOYMENT_REGRESSION`, `RECENT_CONFIG_CHANGE_REGRESSION` |
| `PVC_PRESSURE` | `PVC_PRESSURE`, `BROKER_RESOURCE_PRESSURE` |

### 8. 변경 / 배포

| Incident type | 우선 root cause 후보 |
| --- | --- |
| `CONFIG_CHANGE_REGRESSION` | `RECENT_CONFIG_CHANGE_REGRESSION`, `PIPELINE_CONFIG_INVALID` |
| `SCHEMA_CHANGE_REGRESSION` | `RECENT_SCHEMA_CHANGE_REGRESSION`, `SCHEMA_MISMATCH`, `SINK_CONSTRAINT_VIOLATION` |
| `IMAGE_DEPLOYMENT_REGRESSION` | `RECENT_IMAGE_DEPLOYMENT_REGRESSION`, `DEPLOYMENT_REGRESSION`, `POD_CRASH_LOOP`, `POD_OOM_KILLED` |
| `CREDENTIAL_ROTATION_FAILURE` | `CREDENTIAL_ROTATION_REGRESSION`, `SOURCE_AUTH_EXPIRED`, `SINK_AUTH_EXPIRED` |

### 9. 데이터 품질

| Incident type | 우선 root cause 후보 |
| --- | --- |
| `FRESHNESS_DELAY` | `PIPELINE_FRESHNESS_DELAY`, `SOURCE_DATA_NOT_READY`, `CONSUMER_LAG_SPIKE`, `SINK_WRITE_LATENCY` |
| `VOLUME_ANOMALY` | `UPSTREAM_DATA_VOLUME_ANOMALY`, `SOURCE_DATA_NOT_READY`, `TOPIC_INGRESS_SPIKE` |
| `DUPLICATE_SPIKE` | `PIPELINE_DUPLICATE_SPIKE`, `RECENT_CONFIG_CHANGE_REGRESSION` |
| `NULL_RATE_SPIKE` | `SCHEMA_NULL_RATE_SPIKE`, `RECENT_SCHEMA_CHANGE_REGRESSION`, `UPSTREAM_DATA_VOLUME_ANOMALY` |

### 10. Unknown

| Incident type | 처리 |
| --- | --- |
| `UNKNOWN_NEEDS_MORE_EVIDENCE` | Planner가 추가 evidence를 수집하고, 여전히 부족하면 `UNKNOWN_WITH_EVIDENCE_GAP` |
| `CUSTOMER_OWNED_ESCALATION` | `CUSTOMER_OWNED_ROOT_CAUSE_LIKELY` 또는 고객사 소유 root cause 후보로 escalation |

---

