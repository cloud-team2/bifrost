# FastAPI Agent Server — Catalogs (Failure Types ~ Policy Matrix)

> 요약은 [overview.md](./overview.md). 이 파일은 운영 기준 정적 카탈로그(§6 장애유형 ~ §12 정책 매트릭스)를 담는다. 찾아보는 참조 자료다.

## 6. Catalog: Failure Types


### 1. 목적

이 문서는 Bifrost Agent가 Incident를 1차 분류할 때 사용하는 장애 유형 목록이다.

장애 유형은 “어디에서 문제가 관측되는가”를 기준으로 나눈다. RCA의 최종 원인 후보는 [§8 Root Cause Catalog](#8-catalog-root-cause)를 따른다.

Classifier가 만든 `incident_type`이 어떤 `root_cause_id` 후보군으로 이어지는지는 [§7 Incident→RootCause Map](#7-catalog-incidentrootcause-map)를 기준으로 한다.

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

## 7. Catalog: Incident→RootCause Map


### 1. 목적

이 문서는 Classifier가 만든 `incident_type`이 RCA Agent의 `root_cause_id` 후보군으로 어떻게 이어지는지 정의한다.

`incident_type`은 관측된 증상 분류이고, `root_cause_id`는 evidence matrix로 검증할 원인 후보다. 이름이 같아 보이는 항목도 같은 개념으로 취급하지 않는다.

### 2. 매핑 원칙

1. Classifier는 하나 이상의 `incident_type`을 만들 수 있다.
2. RCA는 이 매핑의 후보군 안에서 root cause를 우선 검증한다.
3. 후보군 밖 root cause가 필요하면 Planner가 추가 evidence 수집 사유를 남긴다.
4. 모든 최종 root cause는 [§8 Root Cause Catalog](#8-catalog-root-cause)와 [§9 Evidence Matrix](#9-catalog-evidence-matrix)를 만족해야 한다.
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

## 8. Catalog: Root Cause


### 1. 목적

RCA Agent는 이 문서의 root cause id 중 하나만 선택한다. catalog에 없는 원인은 생성하지 않고 `UNKNOWN_WITH_EVIDENCE_GAP`으로 보고한다.

각 root cause는 [§9 Evidence Matrix](#9-catalog-evidence-matrix)의 필수 evidence 기준을 만족해야 확정 후보가 될 수 있다. 단, `UNKNOWN_WITH_EVIDENCE_GAP`, `MULTIPLE_POSSIBLE_CAUSES`, `CUSTOMER_OWNED_ROOT_CAUSE_LIKELY`는 확정 원인이 아니라 보류/에스컬레이션 상태이므로 별도 evidence matrix heading을 두지 않는다.

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

## 9. Catalog: Evidence Matrix


### 1. 목적

이 문서는 root cause별로 어떤 evidence가 있어야 RCA 후보로 인정할 수 있는지 정의한다.

RCA Agent는 점수만 보고 원인을 확정하지 않는다. Required evidence가 없으면 confidence가 높아도 확정하지 않는다.

### 2. Evidence 유형

| 유형 | 의미 |
| --- | --- |
| Required | 없으면 해당 root cause를 확정할 수 없음 |
| Supporting | confidence를 높이는 보조 근거 |
| Negative | 해당 root cause 가능성을 낮추는 반증 |
| Exclusion | 다른 root cause를 배제하는 근거 |

### 3. Confidence 기준

초기 기준은 다음과 같다.

| Confidence | 의미 | 처리 |
| --- | --- | --- |
| `>= 0.80` | 강한 후보 | 대응안 생성 가능 |
| `0.60 - 0.79` | 유력하지만 추가 확인 필요 | 추가 evidence 또는 제한적 대응 |
| `< 0.60` | 확정 불가 | unknown 또는 추가 조사 |

기준은 과거 incident replay로 보정한다. 임의로 threshold를 바꾸지 않는다.

### 4. Source Root Cause

#### 4.1 `SOURCE_DB_CONNECTION_TIMEOUT`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| source connection timeout 증가 | Required | `pipeline_source_connection_timeout_total` 증가 |
| pipeline extract/read 단계 timeout log | Required | `extract_users` task `ConnectionTimeout` |
| pipeline read latency 증가 | Required | extract duration p95 증가 |
| sink write 단계 정상 | Supporting | sink write latency 정상 |
| 최근 source credential rotate 없음 | Supporting | auth 변경 없음 |
| sink write timeout 증가 | Negative | source 단독 원인 가능성 낮춤 |
| source metric 정상 | Negative | source timeout 후보 약화 |

판단 주의: source extract timeout을 근거로 sink DB connection limit을 결론내지 않는다.

#### 4.2 `SOURCE_AUTH_EXPIRED`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| auth/permission error log | Required | `AccessDenied`, `token expired` |
| credential rotation 또는 secret 변경 이력 | Supporting | rotate 직후 실패 |
| connection timeout만 존재하고 auth error 없음 | Negative | auth 후보 약화 |

#### 4.3 `SOURCE_READ_LATENCY`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| source read latency 증가 | Required | p95 read latency 증가 |
| extract task duration 증가 | Required | task runtime 증가 |
| downstream 처리 정상 | Supporting | Kafka/sink 지표 정상 |
| source connection failure | Negative | latency가 아니라 connectivity 후보 우선 |

#### 4.4 `SOURCE_DATA_NOT_READY`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| source watermark 정체 또는 expected partition 미생성 | Required | source watermark가 SLA 시간 이상 갱신되지 않음 |
| pipeline extract 결과 empty batch 반복 | Required | row count 0 또는 기준 대비 급감 |
| upstream schedule 지연 또는 source 생성 job 지연 | Supporting | source owner schedule delay |
| source read timeout 또는 auth failure | Negative | 데이터 미준비보다 연결/auth 후보 우선 |

#### 4.5 `SOURCE_NETWORK_REACHABILITY`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| Bifrost에서 source endpoint reachability 실패 | Required | DNS/TCP connect failure summary |
| 여러 pipeline에서 같은 source endpoint 연결 실패 | Supporting | shared dependency timeout |
| 고객사 source 내부 지표 정상이나 network path error 존재 | Supporting | network error code 증가 |
| auth error 또는 query error만 존재 | Negative | network 후보 약화 |

### 5. Pipeline / Connector Root Cause

#### 5.1 `CONNECTOR_TASK_FAILED`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| connector task status `FAILED` | Required | Kafka Connect task 상태 |
| task trace 또는 worker log | Required | exception stack summary |
| 최근 connector config/schema 변경 | Supporting | 변경 이후 실패 |
| worker 전체 장애 | Negative | worker/root infra 후보 우선 |

#### 5.2 `PIPELINE_TASK_RETRY_EXHAUSTED`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| retry count exhausted | Required | max retry reached |
| 동일 task 반복 실패 | Required | retry history |
| transient dependency error | Supporting | source/sink timeout |
| 첫 실패이며 retry 여지 있음 | Negative | exhausted 아님 |

#### 5.3 `SCHEMA_MISMATCH`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| serialization/deserialization/schema error | Required | incompatible schema |
| schema version 변경 이력 | Supporting | recent subject version |
| 데이터 샘플 구조 변화 | Supporting | field type mismatch |
| schema 변경 없음 | Negative | 후보 약화 |

#### 5.4 `CONNECTOR_WORKER_REBALANCE_LOOP`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| Connect worker rebalance 이벤트 반복 | Required | rebalance count 급증 |
| task assignment가 반복적으로 변경 | Required | task revoked/assigned loop |
| worker pod restart 또는 network flap | Supporting | worker instability |
| 단일 connector task exception만 존재 | Negative | task 자체 실패 후보 우선 |

#### 5.5 `PIPELINE_CONFIG_INVALID`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| config validation error 또는 invalid option log | Required | unknown config, invalid converter |
| 최근 pipeline/connector config 변경 | Required | config diff 존재 |
| rollback 또는 이전 config에서 정상 동작 | Supporting | config regression evidence |
| 변경 없이 dependency timeout만 존재 | Negative | config 후보 약화 |

### 6. Kafka Root Cause

#### 6.1 `CONSUMER_LAG_SPIKE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| consumer lag 급증 | Required | lag p95 증가 |
| offset progression 둔화 | Required | commit rate 감소 |
| topic ingress 증가 | Supporting | incoming messages 증가 |
| consumer pod resource pressure | Supporting | CPU/memory saturation |
| broker 장애 evidence | Negative | broker 원인 우선 |

#### 6.2 `BROKER_RESOURCE_PRESSURE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| broker resource saturation | Required | disk, CPU, network |
| broker request latency 증가 | Required | produce/fetch latency |
| under-replicated partition 증가 | Supporting | ISR 변화 |
| consumer만 느림 | Negative | consumer 원인 우선 |

#### 6.3 `PARTITION_IMBALANCE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| broker별 partition 또는 leader skew | Required | distribution imbalance |
| 특정 broker만 resource pressure | Supporting | broker hot spot |
| Cruise Control proposal 개선 예상 | Supporting | rebalance proposal |
| 균등 분산 상태 | Negative | 후보 배제 |

#### 6.4 `TOPIC_INGRESS_SPIKE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| topic ingress rate 급증 | Required | messages in/sec 또는 bytes in/sec 증가 |
| upstream volume 증가와 시간 상관 | Required | source row count 급증 |
| consumer lag가 ingress 증가 직후 동반 | Supporting | lag start time correlation |
| ingress 정상인데 consumer 처리량만 감소 | Negative | consumer/sink 후보 우선 |

#### 6.5 `CONSUMER_REBALANCE_LOOP`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| consumer group rebalance 반복 | Required | rebalance event count 증가 |
| member join/leave 반복 또는 assignment churn | Required | member id 변경 반복 |
| pod restart 또는 heartbeat/session timeout | Supporting | consumer stability issue |
| lag 증가만 있고 rebalance 없음 | Negative | lag spike 원인으로 부족 |

### 7. Sink Root Cause

#### 7.1 `SINK_DB_CONNECTION_TIMEOUT`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| sink write timeout 증가 | Required | sink connector write timeout |
| sink dependency connection error | Required | reachability or pool error |
| source read 정상 | Supporting | upstream 정상 |
| sink write latency 증가 | Supporting | write duration p95 증가 |
| source extract timeout | Negative | source 후보 우선 |

#### 7.2 `SINK_WRITE_LATENCY`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| sink write latency 증가 | Required | write p95 증가 |
| connector sink task 처리시간 증가 | Required | flush/batch duration 증가 |
| source/Kafka 정상 | Supporting | upstream 정상 |
| sink auth error | Negative | auth 후보 우선 |

#### 7.3 `SINK_AUTH_EXPIRED`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| sink auth/permission error log | Required | `AccessDenied`, `token expired` |
| credential rotation 또는 secret 변경 이력 | Supporting | rotate 직후 실패 |
| connection timeout만 존재하고 auth error 없음 | Negative | auth 후보 약화 |

#### 7.4 `SINK_CONSTRAINT_VIOLATION`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| sink constraint 또는 duplicate key error | Required | unique constraint violation |
| schema 또는 transform 변경 이력 | Supporting | field 변경 후 write error |
| 동일 record 반복 실패 | Supporting | poison record 가능성 |
| sink timeout/latency만 존재 | Negative | constraint 후보 약화 |

### 8. Infra Root Cause

#### 8.1 `POD_OOM_KILLED`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| pod last state OOMKilled | Required | Kubernetes status |
| restart count 증가 | Required | restart count delta |
| memory usage limit 근접 | Supporting | container memory metric |
| app-level source timeout만 존재 | Negative | source 후보 우선 |

#### 8.2 `DEPLOYMENT_REGRESSION`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| 배포 이후 error/latency 증가 | Required | rollout time correlation |
| image/config diff | Required | change record |
| rollback 후 개선 | Supporting | after evidence |
| 배포 전부터 문제 지속 | Negative | 변경 원인 약화 |

#### 8.3 `POD_CRASH_LOOP`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| pod `CrashLoopBackOff` 또는 반복 restart | Required | restart count 증가 |
| container termination reason과 app error summary | Required | exit code, startup failure |
| 최근 config/image 변경 | Supporting | rollout 직후 crash |
| 정상 pod 상태에서 app-level timeout만 존재 | Negative | pod crash 후보 약화 |

#### 8.4 `NODE_PRESSURE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| node condition pressure | Required | MemoryPressure, DiskPressure, PIDPressure |
| affected pod scheduling/eviction event | Required | eviction 또는 pending 증가 |
| 같은 node의 여러 workload 영향 | Supporting | node-local symptom |
| 특정 app pod만 실패 | Negative | app/config 후보 우선 |

#### 8.5 `PVC_PRESSURE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| PVC 사용량 또는 I/O latency 임계치 초과 | Required | volume usage high, fsync latency |
| pod log에 disk full 또는 write failure | Required | no space left on device |
| broker 또는 DB workload와 시간 상관 | Supporting | storage-backed workload 영향 |
| CPU/memory pressure만 존재 | Negative | PVC 후보 약화 |

### 9. Change Root Cause

#### 9.1 `RECENT_CONFIG_CHANGE_REGRESSION`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| config 변경 시점 이후 error/latency 증가 | Required | change time correlation |
| 변경 diff와 증상 계층이 연결됨 | Required | connector task config diff |
| rollback 또는 이전 config로 개선 | Supporting | after evidence |
| 변경 전부터 동일 증상 지속 | Negative | config regression 후보 약화 |

#### 9.2 `RECENT_SCHEMA_CHANGE_REGRESSION`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| schema version 변경 이후 schema/serialization error 증가 | Required | subject version change |
| compatibility check 실패 또는 필드 타입 변화 | Required | incompatible schema |
| affected topic/connector가 변경 subject를 사용 | Supporting | topology match |
| schema 변경 없음 | Negative | schema regression 후보 약화 |

#### 9.3 `RECENT_IMAGE_DEPLOYMENT_REGRESSION`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| image rollout 이후 error/latency/restart 증가 | Required | deployment rollout event |
| 이전 image 대비 config/runtime 차이 | Required | image tag diff |
| rollback 후 개선 | Supporting | after evidence |
| rollout 전부터 문제 지속 | Negative | image regression 후보 약화 |

#### 9.4 `CREDENTIAL_ROTATION_REGRESSION`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| credential rotation 이후 auth failure 증가 | Required | rotate time correlation |
| affected dependency가 해당 credential을 사용 | Required | dependency ownership match |
| rotation audit 또는 secret version 변경 | Supporting | version diff |
| auth error 없이 timeout만 존재 | Negative | credential 후보 약화 |

### 10. Data Quality Root Cause

#### 10.1 `UPSTREAM_DATA_VOLUME_ANOMALY`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| source row count 또는 topic ingress가 기준 대비 급변 | Required | volume z-score 이상 |
| upstream schedule/change와 시간 상관 | Supporting | upstream batch size change |
| pipeline 처리량 저하 없이 입력만 변동 | Supporting | downstream 정상 |
| pipeline failure 때문에 output만 감소 | Negative | pipeline 후보 우선 |

#### 10.2 `PIPELINE_DUPLICATE_SPIKE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| duplicate count 또는 duplicate key error 증가 | Required | duplicate metric 증가 |
| retry/replay/backfill 또는 idempotency gap | Required | repeated processing evidence |
| 최근 transform/config 변경 | Supporting | key derivation change |
| upstream부터 duplicate가 존재 | Negative | upstream data quality 후보 우선 |

#### 10.3 `PIPELINE_FRESHNESS_DELAY`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| end-to-end freshness 또는 watermark delay 증가 | Required | freshness SLA breach |
| pipeline stage 중 병목 단계 식별 | Required | source/Kafka/sink stage duration |
| lag 또는 sink latency 동반 | Supporting | downstream bottleneck |
| source 데이터 미생성만 확인 | Negative | source data not ready 후보 우선 |

#### 10.4 `SCHEMA_NULL_RATE_SPIKE`

| Evidence | 유형 | 예시 |
| --- | --- | --- |
| 특정 field null rate 급증 | Required | null rate metric 증가 |
| schema/source/transform 변경과 시간 상관 | Required | field mapping change |
| downstream validation error 동반 | Supporting | bad record increase |
| 전체 volume 급감만 존재 | Negative | volume anomaly 후보 우선 |

### 11. 공통 원인 판단

여러 Incident를 하나의 root cause로 묶으려면 다음 중 최소 하나 이상의 직접 evidence가 필요하다.

| 기준 | Required signal |
| --- | --- |
| shared dependency | 같은 source/sink/Kafka cluster/node 사용 |
| topology | 같은 upstream/downstream 경로 |
| common change | 같은 배포/config/schema 변경 이후 발생 |
| time window | 같은 시간대에 증상 시작 |

시간만 겹치는 것은 충분하지 않다. topology나 dependency evidence가 필요하다.

### 12. Replay 보정

운영 후에는 incident replay dataset으로 다음을 보정한다.

- required evidence 누락 시 confidence cap
- supporting evidence weight
- negative evidence penalty
- alert correlation window
- action recommendation threshold

보정값 변경은 catalog version과 함께 기록한다.

---

## 10. Catalog: Correlation Rules


### 1. 목적

Alert는 개별 이상 신호이고 Incident는 운영자가 대응하는 사건 단위다. 이 문서는 여러 Alert를 하나의 Incident 또는 Incident group으로 묶는 기준을 정의한다.

Correlation은 LLM 판단이 아니라 deterministic rule engine이 먼저 수행한다. Agent는 그 결과를 검토하고 필요한 경우 evidence를 추가한다.

### 2. 병합 후보 기준

Alert 병합 후보는 다음 네 축으로 판단한다.

| 축 | 설명 |
| --- | --- |
| time window | 비슷한 시간대에 시작했는가 |
| topology | 같은 pipeline, connector, topic, consumer group, dependency를 공유하는가 |
| shared change | 같은 배포, config, schema, credential 변경 이후 발생했는가 |
| symptom direction | upstream 문제가 downstream 증상을 만들 수 있는가 |

### 3. 병합 전략

| 전략 | 설명 | 사용 조건 |
| --- | --- | --- |
| rule-based immediate | alert 수신 즉시 규칙과 score로 병합 | alert 수가 적고 빠른 화면 반영 필요 |
| serial queue processing | alert를 queue에 넣고 순차 처리 | race condition과 중복 incident 방지 우선 |
| urgent plus window | 긴급 alert는 즉시 incident, 비긴급 alert는 window 내 병합 | 운영 영향 alert와 warning alert가 섞인 환경 |

v1 권장은 `urgent plus window`다. 고객 영향이 큰 alert는 즉시 Incident로 올리고, warning/secondary signal은 짧은 window 안에서 관련 신호로 병합한다.

### 4. Correlation Score

초기 score는 다음 항목을 사용한다. 가중치는 replay data로 보정한다.

| 항목 | 초기 weight | 설명 |
| --- | --- | --- |
| same pipeline | 0.25 | 같은 pipeline id |
| same dependency | 0.25 | 같은 source/sink/Kafka cluster |
| topology adjacency | 0.20 | upstream/downstream 관계 |
| same change | 0.20 | 같은 변경 이벤트 이후 발생 |
| time proximity | 0.10 | 시작 시간이 가까움 |

단, time proximity만으로 병합하지 않는다.

### 5. 병합 Decision

| Decision | 기준 |
| --- | --- |
| `merge_into_existing_incident` | dependency/topology/change 중 하나 이상과 time window가 맞음 |
| `create_new_incident` | 공통 근거가 없거나 다른 topology |
| `attach_as_related_signal` | 원인은 다를 수 있으나 같은 incident 분석에 참고 가치 있음 |
| `create_incident_group` | 여러 incident가 하나의 root cause를 공유할 가능성이 높음 |

### 6. Source 장애의 Downstream 증상

Source 장애는 downstream에서 여러 증상으로 나타날 수 있다.

예시:

```text
source timeout
  -> extract task failure
  -> Kafka topic ingress 감소
  -> downstream freshness delay
  -> sink write volume 감소
```

이 경우 downstream alert를 별도 root cause로 확정하기 전에 source evidence를 확인한다.

### 7. Sink 장애의 Upstream 영향

Sink 장애는 upstream에는 backlog나 lag로 나타날 수 있다.

예시:

```text
sink write timeout
  -> sink connector retry/backoff
  -> connector task failed
  -> consumer lag 증가
```

이 경우 consumer lag 자체를 root cause로 보지 않고 sink write evidence를 확인한다.

### 8. Incident Group

하나의 root cause가 여러 Incident를 만들 수 있다. 이때는 `incident_group`을 만든다.

사용 조건:

1. 서로 다른 pipeline에서 alert가 발생했다.
2. 같은 dependency, Kafka cluster, node, schema registry, 배포 이벤트 중 하나를 공유한다.
3. 증상 시작 시간이 같은 window 안에 있다.
4. 개별 incident별 증상이 공통 root cause에서 파생될 수 있다.

### 9. 병합 금지 조건

다음 경우에는 같은 시간대여도 병합하지 않는다.

- topology가 완전히 다름
- dependency가 다름
- root cause 후보가 서로 배타적임
- 하나는 customer-owned, 하나는 bifrost-owned로 증거가 명확히 갈림
- 단순 warning noise만 시간상 겹침

### 10. Output Schema

Correlation Engine은 다음 형식으로 결과를 남긴다.

```json
{
  "correlation_id": "corr_001",
  "decision": "create_incident_group",
  "incident_scope": "incident_group",
  "primary_alert_id": "alert_001",
  "related_alert_ids": ["alert_002", "alert_003"],
  "common_evidence": [
    {
      "type": "shared_dependency",
      "value": "source_db_users"
    }
  ],
  "confidence": 0.82
}
```

이 결과는 RCA 결론이 아니다. Classifier와 RCA가 추가 evidence로 검증한다.

---

## 11. Catalog: Remediation Runbooks


### 1. 목적

이 문서는 root cause별 대응 후보를 정의한다. Remediation Agent는 이 문서의 action template 안에서만 조치 후보를 만든다.

실행 가능 여부는 [§12 Policy Matrix](#12-catalog-policy-matrix)와 Spring Boot Operations Backend가 최종 판단한다.

### 2. 원칙

1. Remediation Agent는 조치 후보만 만든다.
2. 승인 필요 여부는 Policy Guard가 판단한다.
3. 실행은 Executor와 Spring Boot Operations Backend만 한다.
4. 고객사 소유 영역은 직접 수정하지 않고 escalation한다.
5. 데이터 손실 가능성이 있는 조치는 기본 금지 또는 변경관리 대상이다.

Runbook의 `Action`은 항상 Spring Boot mutation tool을 뜻하지 않는다. 추가 evidence 수집은 `workflow_action`, 고객사/플랫폼 전달은 `escalation`, 알림은 `notification`, 여러 조치 후보로 분해해야 하는 의도는 `composite_action`으로 둔다. 실제 실행 가능한 단일 tool은 [§4 Tool Catalog](tool-catalog.md#4-tool-catalog)의 `runtime_tool` catalog에 등록되어야 한다.

### 3. Source 계층

#### `SOURCE_DB_CONNECTION_TIMEOUT`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_source_timeout_evidence` | source timeout log/metric 추가 수집 | read_only |
| `escalate_to_customer_owner` | 고객사 담당자에게 evidence 전달 | allow |
| `pause_non_critical_pipeline` | downstream 압박 완화를 위해 비긴급 pipeline 일시 중지 | approval |
| `restart_connector_task` | timeout이 transient이고 task가 failed일 때 재시작 | approval |

금지:

- source DB connection limit 직접 변경
- source DB query 실행
- 고객사 DB restart

#### `SOURCE_AUTH_EXPIRED`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_auth_error_evidence` | auth error와 변경 이력 수집 | read_only |
| `escalate_credential_rotation` | credential owner에게 갱신 요청 | allow |
| `pause_pipeline` | 반복 실패로 downstream noise가 클 때 일시 중지 | approval |

금지:

- secret 원문 조회
- credential 임의 변경

### 4. Pipeline / Connector 계층

#### `CONNECTOR_TASK_FAILED`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `restart_connector_task` | 실패 task 재시작 | approval |
| `pause_connector` | 반복 실패로 영향이 커질 때 일시 중지 | approval |
| `resume_connector` | 원인 해소 후 재개 | approval |
| `collect_connector_trace` | task trace와 worker log 수집 | read_only |

주의:

- 같은 원인으로 반복 실패하면 재시작 루프를 만들지 않는다.
- schema/config 문제면 restart보다 변경관리로 넘긴다.

#### `SCHEMA_MISMATCH`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_schema_changes` | subject version과 compatibility 확인 | read_only |
| `pause_pipeline` | 잘못된 데이터 확산 방지 | approval |
| `rollback_pipeline` | schema 변경 또는 배포 rollback | change_management |

금지:

- schema compatibility 강제 변경
- sink table 임의 변경

### 5. Kafka 계층

#### `CONSUMER_LAG_SPIKE`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `get_consumer_lag` | lag 상세 확인 | read_only |
| `scale_consumer_deployment` | consumer replica 증가 | approval |
| `create_rebalance_proposal` | broker imbalance가 동반될 때 proposal 생성 | approval |
| `pause_low_priority_pipeline` | 중요도가 낮은 pipeline 일시 중지 | approval |

주의:

- topic ingress spike인지 consumer 처리 저하인지 먼저 구분한다.
- scale-out 전 consumer가 병렬 처리 가능한 구조인지 확인한다.

#### `BROKER_RESOURCE_PRESSURE`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_broker_metrics` | broker별 CPU/disk/network 확인 | read_only |
| `create_rebalance_proposal` | Cruise Control proposal 생성 | approval |
| `approve_rebalance` | proposal 승인 | approval |
| `escalate_platform_capacity` | capacity 부족 시 platform team escalation | allow |

금지:

- broker 강제 재시작
- topic delete

### 6. Sink 계층

#### `SINK_DB_CONNECTION_TIMEOUT`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_sink_timeout_evidence` | sink write timeout과 latency 수집 | read_only |
| `pause_connector` | sink 보호를 위해 write 중단 | approval |
| `resume_connector` | sink 회복 후 재개 | approval |
| `escalate_to_customer_owner` | sink owner에게 evidence 전달 | allow |

금지:

- sink DB connection limit 변경
- sink DB restart
- 임의 SQL 실행

#### `SINK_WRITE_LATENCY`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `reduce_pipeline_pressure` | 비긴급 pipeline 일시 중지 또는 rate 완화 | approval |
| `pause_connector` | sink 보호 | approval |
| `collect_sink_write_metrics` | write latency와 retry/backoff 수집 | read_only |

### 7. Infra 계층

#### `POD_OOM_KILLED`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_pod_status` | pod status와 restart count 확인 | read_only |
| `collect_memory_metrics` | memory usage와 limit 확인 | read_only |
| `scale_consumer_deployment` | 처리 병렬화로 pod pressure 완화 | approval |
| `rollback_pipeline` | 배포 이후 OOM이면 rollback | change_management |

금지:

- pod exec
- container 내부 파일 수정

#### `DEPLOYMENT_REGRESSION`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_recent_changes` | rollout, image, config diff 확인 | read_only |
| `rollback_pipeline` | 문제 배포 rollback | change_management |
| `pause_pipeline` | 영향 확산 차단 | approval |

### 8. Unknown

#### `UNKNOWN_WITH_EVIDENCE_GAP`

| Action | 설명 | 정책 |
| --- | --- | --- |
| `collect_additional_evidence` | Planner가 추가 evidence 계획 | read_only |
| `escalate_to_operator` | 확정 불가 상태로 운영자에게 전달 | allow |

Unknown 상태에서는 mutation action을 만들지 않는다.

### 9. Action Template Schema

```json
{
  "action_id": "act_001",
  "root_cause_id": "CONNECTOR_TASK_FAILED",
  "action_name": "restart_connector_task",
  "action_type": "runtime_tool",
  "tool_name": "restart_connector_task",
  "risk": "medium",
  "requires_human_approval": true,
  "reason": "connector task is FAILED and no schema/config regression evidence found",
  "expected_effect": "task restart may clear transient failure",
  "rollback_plan": "pause connector if task fails again",
  "estimated_duration": "약 30초 (예상, FR-022 표시용)"
}
```

`tool_name`은 `action_type=runtime_tool`일 때 필수다. `workflow_action`, `notification`, `escalation`, `composite_action`은 `tool_name`을 비워둘 수 있으며, Tool Client Registry 또는 Supervisor가 구체적인 read-only tool plan, notification request, runtime tool 후보로 변환한다.

Action type 예시:

| Action | action_type | 해석 |
| --- | --- | --- |
| `collect_connector_trace` | `workflow_action` | `get_connector_task_trace`와 관련 로그 수집 계획 |
| `pause_low_priority_pipeline` | `composite_action` | 대상 pipeline을 선택한 뒤 `pause_pipeline` 후보로 분해 |
| `escalate_to_customer_owner` | `escalation` | evidence summary를 고객사 owner에게 전달 |
| `send_operator_notification` | `notification` | 운영자 알림 생성 |

### 10. Versioning

Runbook 변경은 root cause catalog, policy matrix, tool catalog와 함께 검토한다. 특정 action을 새로 허용할 때는 approval 정책과 audit 필드도 같이 정의한다.

---

## 12. Catalog: Policy Matrix


### 1. 목적

이 문서는 Agent action의 위험도와 승인 기준을 정의한다. Policy Guard는 이 문서를 기준으로 `allow`, `require_approval`, `require_change_management`, `deny` 중 하나를 선택한다.

**최종 집행·정본은 Spring Boot의 [server.md §7.1 Operation Allowlist](../backend-springboot/server.md#71-operation-allowlist-집행-경계-단일-출처)다.** 이 policy matrix는 그 allowlist를 미러링한 *사전 판단*이며, Spring이 실행 직전 같은 기준을 재검증한다(불일치 시 Spring 기준 우선).

### 2. Decision

| Decision | 의미 |
| --- | --- |
| `allow` | 자동 실행 가능 |
| `require_approval` | 사람 승인 후 실행 가능 |
| `require_change_management` | change ticket, 실행 window, rollback plan 필요 |
| `deny` | 실행 금지 |

### 3. Risk Level

| Risk | 설명 | 기본 decision |
| --- | --- | --- |
| `read_only` | 상태 조회, 로그/메트릭 조회 | `allow` |
| `low` | 외부 상태 변경 없음, 내부 workflow 상태만 변경 | `allow` |
| `medium` | 제한적 runtime 상태 변경 | `require_approval` |
| `high` | 데이터 재처리, rollback, 광범위한 영향 | `require_change_management` |
| `forbidden` | 데이터 삭제, secret 노출, shell 실행 | `deny` |

### 4. Tool별 기본 정책

`runtime_tool`은 tool allowlist 기준으로 판단하고, `workflow_action`/`composite_action`/`notification`/`escalation`은 action catalog 기준으로 판단한다.

| Tool 또는 Action | Risk | Decision |
| --- | --- | --- |
| `get_pipeline_logs` | read_only | allow |
| `get_metrics` | read_only | allow |
| `get_traces` | read_only | allow |
| `get_connector_status` | read_only | allow |
| `get_connector_task_trace` | read_only | allow |
| `get_consumer_lag` | read_only | allow |
| `get_broker_metrics` | read_only | allow |
| `get_pod_status` | read_only | allow |
| `get_db_connection_status` | read_only | allow |
| `collect_*_evidence` | read_only | allow |
| `collect_connector_trace` | read_only | allow |
| `collect_schema_changes` | read_only | allow |
| `collect_broker_metrics` | read_only | allow |
| `collect_sink_write_metrics` | read_only | allow |
| `collect_pod_status` | read_only | allow |
| `collect_memory_metrics` | read_only | allow |
| `collect_recent_changes` | read_only | allow |
| `collect_additional_evidence` | read_only | allow |
| `send_operator_notification` | low | allow |
| `create_ticket` | low | allow |
| `escalate_to_customer_owner` | low | allow |
| `escalate_credential_rotation` | low | allow |
| `escalate_platform_capacity` | low | allow |
| `escalate_to_operator` | low | allow |
| `pause_non_critical_pipeline` | medium | require_approval |
| `pause_low_priority_pipeline` | medium | require_approval |
| `reduce_pipeline_pressure` | medium | require_approval |
| `restart_connector_task` | medium | require_approval |
| `restart_connector` | medium | require_approval |
| `pause_connector` | medium | require_approval |
| `resume_connector` | medium | require_approval |
| `scale_consumer_deployment` | medium | require_approval |
| `rollout_restart_deployment` | medium | require_approval |
| `pause_pipeline` | medium | require_approval |
| `resume_pipeline` | medium | require_approval |
| `create_rebalance_proposal` | medium | require_approval |
| `approve_rebalance` | medium | require_approval |
| `refresh_rebalance` | medium | require_approval |
| `backfill_pipeline` | high | require_change_management |
| `rollback_pipeline` | high | require_change_management |
| `rollback_deployment` | high | require_change_management |

### 5. 변경관리 대상

다음 조건 중 하나라도 해당하면 `require_change_management`로 올린다.

- 데이터 재처리
- rollback
- partition/retention/schema 변경
- connector config overwrite
- customer-visible downtime 가능성
- 여러 pipeline에 동시에 영향
- rollback plan이 필요한 작업

### 6. Severity 보정

`incident.severity`는 플랫폼과 동일하게 **`WARNING`/`CRITICAL` 2단계**다([기능명세서 부록 B.7](../../spec.md#b7-인시던트-자동-생성-및-그룹화-규칙)). severity는 action을 더 위험한 decision으로 **올릴 때만** 사용하고, 자동으로 낮추는 데는 쓰지 않는다.

| Severity | 정책 보정 |
| --- | --- |
| `CRITICAL` | approval 요청 우선순위를 높이고, customer-visible action은 change management 검토 |
| `WARNING` | runtime mutation은 최소 approval 유지, 기본 decision 유지 (자동 허용으로 낮추지 않음) |

> 에이전트와 플랫폼은 같은 2단계 severity를 쓴다. 별도의 4단계(critical/high/medium/low) 축은 두지 않는다 — 과거 문서의 4단계 표기는 폐기한다. RCA 분석으로 보정된 severity(있으면)는 [Spring Report Support API](../../api/springboot.md#24-report-support-api)의 `PATCH .../incidents/{id}/rca`로 incident에 기록한다.

### 7. Deny 대상

다음 작업은 승인 여부와 무관하게 deny한다.

| 작업 | 이유 |
| --- | --- |
| pod exec | shell 권한 노출 |
| arbitrary SQL | 고객사 DB 직접 조작 |
| secret 원문 조회 | credential 노출 |
| topic delete | 데이터 손실 |
| namespace/PVC delete | 인프라 손상 |
| DB truncate | 데이터 손실 |
| LLM 생성 manifest 직접 apply | 검증 불가 |

### 8. Approval 검증 조건

`require_approval` action은 다음을 모두 만족해야 한다.

| 조건 | 설명 |
| --- | --- |
| approver 권한 | 승인자가 project/resource 권한 보유 |
| action match | 승인된 tool과 실제 tool 일치 |
| params hash match | 승인 당시 parameter와 실행 parameter 일치 |
| expiry | 승인 유효기간 이내 |
| single-use | 이미 사용된 승인 아님 |
| reason | 승인 사유 기록 |

### 9. Change Management 검증 조건

`require_change_management` action은 다음을 모두 만족해야 한다.

| 조건 | 설명 |
| --- | --- |
| change ticket | 승인된 change ticket 존재 |
| execution window | 현재 시간이 실행 window 안 |
| rollback plan | 실패 시 되돌림 계획 존재 |
| impact analysis | 영향 범위 기록 |
| verifier plan | 실행 후 검증 방법 존재 |

### 10. Policy Guard Output

```json
{
  "action_id": "act_001",
  "action_type": "runtime_tool",
  "tool_name": "restart_connector_task",
  "risk": "medium",
  "decision": "require_approval",
  "reason": "connector task restart changes runtime state",
  "required_approver": "project_operator"
}
```

### 11. 예외 처리

정책이 불명확하면 `allow`로 낮추지 않는다.

| 상황 | 처리 |
| --- | --- |
| action 영향 범위 불명확 | require_approval |
| 데이터 손실 가능성 불명확 | require_change_management |
| catalog에 없는 `runtime_tool` | deny |
| catalog에 없는 workflow/composite/notification/escalation action | Remediation output reject |
| approval scope 불일치 | deny |
| change window 종료 | deny 또는 대기 |

### 12. Versioning

Policy 변경은 audit와 replay test에 영향을 준다. 새로운 mutation tool을 추가할 때는 이 문서와 [§4 Tool Catalog](tool-catalog.md#4-tool-catalog)를 함께 갱신한다.

---
