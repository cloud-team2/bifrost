---
doc_id: catalog:catalog-evidence-matrix
doc_type: catalog
title: Catalog — Evidence Matrix (§9)
tags: [catalog, evidence, rca]
source: curated
---

# Catalog — Evidence Matrix (§9)

> FastAPI Agent 카탈로그 · 개요 [overview](../overview.md) · 원리 [agent-principles](../agent-principles.md). **카탈로그**: [failure-types](./catalog-failure-types.md) · [incident→rootcause](./catalog-incident-root-cause-map.md) · [root-causes](./catalog-root-causes.md) · [evidence-matrix](./catalog-evidence-matrix.md) · [correlation-rules](./catalog-correlation-rules.md) · [runbooks](./catalog-remediation-runbooks.md) · [policy-matrix](./catalog-policy-matrix.md)

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
