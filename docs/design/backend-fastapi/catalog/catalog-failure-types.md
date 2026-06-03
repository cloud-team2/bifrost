# Catalog — Failure Types (§6)

> FastAPI Agent 카탈로그 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **카탈로그**: [failure-types](./catalog-failure-types.md) · [incident→rootcause](./catalog-incident-root-cause-map.md) · [root-causes](./catalog-root-causes.md) · [evidence-matrix](./catalog-evidence-matrix.md) · [correlation-rules](./catalog-correlation-rules.md) · [runbooks](./catalog-remediation-runbooks.md) · [policy-matrix](./catalog-policy-matrix.md)

## 6. Catalog: Failure Types


### 1. 목적

이 문서는 Bifrost Agent가 Incident를 1차 분류할 때 사용하는 장애 유형 목록이다.

장애 유형은 “어디에서 문제가 관측되는가”를 기준으로 나눈다. RCA의 최종 원인 후보는 [§8 Root Cause Catalog](catalog-root-causes.md#8-catalog-root-cause)를 따른다.

Classifier가 만든 `incident_type`이 어떤 `root_cause_id` 후보군으로 이어지는지는 [§7 Incident→RootCause Map](catalog-incident-root-cause-map.md#7-catalog-incidentrootcause-map)를 기준으로 한다.

### 2. 분류 원칙

1. 분류는 관측 계층 기준이다.
2. 하나의 Incident는 여러 유형을 가질 수 있다.
3. downstream 증상은 upstream 원인을 가릴 수 있으므로 topology를 함께 본다.
4. 고객사 소유 영역은 직접 복구 대상과 분리한다.
5. 알 수 없는 유형은 `UNKNOWN_NEEDS_MORE_EVIDENCE`로 둔다.

### 3. Source 계층

| Incident type | 설명 | 대표 신호 |
| --- | --- | --- |
| `SOURCE_CONNECTION_TIMEOUT` | source dependency 연결 지연 또는 timeout | read timeout, connection timeout, reachability failure |
| `SOURCE_AUTH_FAILURE` | source credential, 권한, token 문제 | auth denied, expired token, permission error |
| `SOURCE_READ_LATENCY` | source read 단계 지연 | extract duration 증가, source read latency 증가 |
| `SOURCE_DATA_NOT_AVAILABLE` | source에서 기대 데이터가 생성되지 않음 | empty batch, watermark 정체 |

고객사 DB 내부 lock, index, query tuning은 Agent의 직접 복구 대상이 아니다. pipeline 관점에서 관측 가능한 증거를 정리해 에스컬레이션한다.

### 4. Pipeline / Connector 계층

| Incident type | 설명 | 대표 신호 |
| --- | --- | --- |
| `PIPELINE_TASK_FAILED` | pipeline task 또는 job 실패 | task failed, retry exhausted |
| `CONNECTOR_TASK_FAILED` | Kafka Connect connector task 실패 | task FAILED, trace 포함 |
| `CONNECTOR_WORKER_UNHEALTHY` | Connect worker 자체 상태 이상 | worker unavailable, rebalance loop |
| `PIPELINE_RETRY_BACKOFF` | retry/backoff로 처리 지연 | retry count 증가, backoff duration 증가 |
| `SCHEMA_MISMATCH` | schema 호환성 또는 serialization 문제 | schema incompatible, deserialization error |

> **Bifrost 매핑 주의**: v1에서 파이프라인은 곧 Connector(들)다. `CONNECTOR_TASK_FAILED`는 Kafka Connect task 실패이고, `PIPELINE_TASK_FAILED`는 오케스트레이션/잡 레벨 실패의 일반 유형이다 — 순수 CDC/EDA에서는 둘이 겹칠 수 있으며, 이때 connector 증거를 우선한다.

### 5. Kafka / Streaming 계층

| Incident type | 설명 | 대표 신호 |
| --- | --- | --- |
| `CONSUMER_LAG_SPIKE` | consumer group lag 급증 | lag p95 증가, offset progression 정체 |
| `TOPIC_INGRESS_SPIKE` | topic 유입량 급증 | messages in/sec 증가 |
| `BROKER_RESOURCE_PRESSURE` | broker CPU, disk, network 압박 | disk usage, request latency, ISR 변화 |
| `PARTITION_IMBALANCE` | partition 또는 broker 부하 불균형 | broker별 partition skew |
| `REBALANCE_LOOP` | consumer group 또는 Connect rebalance 반복 | rebalance count 증가 |

### 6. Sink 계층

| Incident type | 설명 | 대표 신호 |
| --- | --- | --- |
| `SINK_CONNECTION_TIMEOUT` | sink dependency 연결 지연 또는 timeout | write timeout, connection timeout |
| `SINK_AUTH_FAILURE` | sink credential 또는 권한 문제 | auth denied, permission error |
| `SINK_WRITE_LATENCY` | sink write 단계 지연 | write latency, batch duration 증가 |
| `SINK_CONSTRAINT_ERROR` | sink schema/constraint/write validation 문제 | duplicate key, constraint violation |

Sink DB 내부 튜닝은 직접 조치하지 않는다. 단, Bifrost가 소유한 connector retry, pause/resume, task restart는 정책에 따라 가능하다.

### 7. Kubernetes / Infra 계층

| Incident type | 설명 | 대표 신호 |
| --- | --- | --- |
| `POD_OOM_KILLED` | memory 초과로 pod 종료 | OOMKilled, restart count 증가 |
| `POD_CRASH_LOOP` | 반복 재시작 | CrashLoopBackOff |
| `NODE_PRESSURE` | node resource pressure | DiskPressure, MemoryPressure |
| `DEPLOYMENT_ROLLOUT_REGRESSION` | 배포 이후 상태 악화 | rollout event 이후 error 증가 |
| `PVC_PRESSURE` | persistent volume 사용량 또는 I/O 문제 | disk full, high I/O latency |

### 8. 변경 / 배포 계층

| Incident type | 설명 | 대표 신호 |
| --- | --- | --- |
| `CONFIG_CHANGE_REGRESSION` | 설정 변경 이후 장애 | config diff와 시간 상관 |
| `SCHEMA_CHANGE_REGRESSION` | schema 변경 이후 장애 | schema version 변경 후 error |
| `IMAGE_DEPLOYMENT_REGRESSION` | image 배포 이후 장애 | new image rollout 이후 failure |
| `CREDENTIAL_ROTATION_FAILURE` | credential rotate 이후 장애 | auth failure와 rotate time 상관 |

### 9. 데이터 품질 / 관측 지표

| Incident type | 설명 | 대표 신호 |
| --- | --- | --- |
| `FRESHNESS_DELAY` | 데이터 최신성 지연 | watermark delay |
| `VOLUME_ANOMALY` | 데이터량 이상 | batch row count 급감/급증 |
| `DUPLICATE_SPIKE` | 중복 증가 | duplicate count 증가 |
| `NULL_RATE_SPIKE` | null rate 증가 | column null rate 증가 |

데이터 품질 유형은 runtime 장애가 아닐 수 있다. RCA는 source data availability, schema change, pipeline transform 변경을 함께 확인한다.

### 10. Unknown

| Incident type | 사용 조건 |
| --- | --- |
| `UNKNOWN_NEEDS_MORE_EVIDENCE` | 필수 evidence가 부족하거나 catalog에 없는 증상 |
| `CUSTOMER_OWNED_ESCALATION` | 고객사 소유 영역으로 판단되지만 증거 정리가 필요한 경우 |

Unknown은 실패가 아니다. 증거 없이 확정하는 것보다 안전한 결론이다.

---

