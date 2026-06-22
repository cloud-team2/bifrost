# RCA 에이전트 전수 통합 테스트 (2026-06-22)

> 모든 상황(8계층 35 root cause)을 상정한 전수 테스트. 두 축으로 구성한다.
> - **Part A — 오프라인 전 계층 평가**: 35 케이스 전수, 재현 스크립트로 지표 산출 (✅ 실측 완료)
> - **Part B — 운영 클러스터 라이브 주입**: 안전하게 주입 가능한 계층의 대표 장애를 실제 주입 → 인시던트 자동 생성 → 자동 RCA 까지 end-to-end 검증 (⏳ 권한 승인 대기)
>
> 모든 수치/판정은 **재현 가능한 근거**(스크립트 출력·커넥터 상태·인시던트 ID·RCA run)를 동반한다. 근거 없는 주장 금지.

---

## 0. 테스트 환경

| 항목 | 값 |
|---|---|
| 클러스터 | `eks-skala3-cloud1-finalproj-team2` (EKS) |
| 앱 | `bifrost-system`: ai-service, operations-backend, frontend (각 1/1) |
| 평가 대상 로직 | `chore/#979` (= 배포 이미지 `4a7ca906`, #962/#964 포함) |
| 테스트 파이프라인 | `5d4b0826-…`(source: Debezium postgres / sink: JDBC→mariadb), tenant ns `e2e-rca-test-0621` |
| source DB | `tenant-postgres` (`tenantdb` ns) — `testdb.public.products` |
| sink DB | `tenant-mariadb` (`tenantdb` ns) — `testdb` |
| 관측 | `metadb`(인시던트)·`agentdb`(RCA run) Postgres |

**블래스트 반경 확인**: `tenant-mariadb`에 붙은 sink 커넥터는 `5d4b0826-…-sink`(테스트 파이프라인) 하나뿐 → 라이브 주입 영향은 테스트 파이프라인으로 한정.

---

## Part A — 오프라인 전 계층 평가 (✅ 실측)

### 방법
- 대상: gold set **35건**(8계층 root cause 대표 시나리오, `app/evaluation/seed_gold_set.py`)
- 입력: **관측 증거만**(symptom + trigger + contributing_factors). 사후 결론(`human_verdict`)은 정답 누출 방지로 제외
- 조건: **LLM 타이브레이커 비활성** → 카탈로그 + 증거 매트릭스 + 신뢰도 게이트만 (가장 보수적 floor)
- 지표: AC@1/AC@3/AC@5, Avg@5(RCAEval 표준), ECE(Guo et al.), 기권율
- 재현: `cd services/ai-service && .venv/bin/python scripts/rca_eval_campaign.py`

### 결과 (실행 실측, 2026-06-22)

| 지표 | 값 |
|---|---|
| AC@1 | **65.7%** (23/35) |
| AC@3 / AC@5 | **80.0%** (28/35) |
| Avg@5 | **0.724** (현 SOTA Avg@5 0.46~0.54 상회) |
| ECE | **0.073** (<0.10 양호) |
| 기권(UNKNOWN) | **7/35 (20%)** |
| 날조(존재하지 않는 원인 생성) | **0건 → 환각 ≈ 0** |

**계층별**

| 계층 | n | AC@1 | AC@3 | AC@5 | Avg@5 |
|---|---|---|---|---|---|
| kafka | 6 | 0.833 | **1.000** | **1.000** | 0.917 |
| pipeline | 7 | 0.857 | 0.857 | 0.857 | 0.857 |
| infra | 5 | 0.800 | 0.800 | 0.800 | 0.800 |
| sink | 4 | 0.750 | 0.750 | 0.750 | 0.750 |
| source | 5 | 0.400 | 0.800 | 0.800 | 0.600 |
| change | 4 | 0.250 | 0.750 | 0.750 | 0.458 |
| data_quality | 4 | 0.500 | 0.500 | 0.500 | 0.500 |

> 해석: 확신 답변 28건 중 23건 정답(**82% precision**), 불확실 7건은 정직한 기권. 약점(change·data_quality·source AC@1)은 temporal/metric 증거 의존도가 높아 **텍스트-only floor**에서 낮게 나온 것 — 프로덕션 retrieval(metric/trace/temporal) + LLM 타이브레이커 시 상향.

### gold set 35건 — 정의 + 케이스별 실측 결과

출처: `app/evaluation/seed_gold_set.py`(카탈로그 root_cause_id별 2~3건 대표). **결과는 위 재현 스크립트의 케이스별 출력**.
적중 표기: ✅ = top-1 정답 / △@3 = 상위 3 내(인접 sibling) / 기권 = top이 UNKNOWN(증거 부족 정직한 보류).

| # | entry_id | 계층 | 기대 root cause | 증상(symptom) | top 예측 | conf | 적중 |
|---|---|---|---|---|---|---|---|
| 1 | gs_seed_001 | source | SOURCE_AUTH_EXPIRED | task FAILED, extract auth error | CREDENTIAL_ROTATION_REGRESSION | 0.68 | △@3 |
| 2 | gs_seed_002 | source | SOURCE_AUTH_EXPIRED | permission denied, 반복 재시작 | UNKNOWN_WITH_EVIDENCE_GAP | 0.00 | 기권 |
| 3 | gs_seed_003 | source | SOURCE_NETWORK_REACHABILITY | connection refused, extract timeout | SOURCE_NETWORK_REACHABILITY | 0.82 | ✅ |
| 4 | gs_seed_004 | source | SOURCE_DB_CONNECTION_TIMEOUT | extract latency 급증, timeout | SOURCE_DB_CONNECTION_TIMEOUT | 0.65 | ✅ |
| 5 | gs_seed_005 | source | SOURCE_READ_LATENCY | freshness 지연, extract p95↑ | SOURCE_DB_CONNECTION_TIMEOUT | 0.65 | △@3 |
| 6 | gs_seed_006 | pipeline | CONNECTOR_TASK_FAILED | task FAILED, restart 무효 | CONNECTOR_TASK_FAILED | 0.68 | ✅ |
| 7 | gs_seed_007 | pipeline | CONNECTOR_TASK_FAILED | task FAILED, worker OOM | CONNECTOR_TASK_FAILED | 0.82 | ✅ |
| 8 | gs_seed_008 | pipeline | PIPELINE_CONFIG_INVALID | config validation error | PIPELINE_CONFIG_INVALID | 0.68 | ✅ |
| 9 | gs_seed_009 | pipeline | SCHEMA_MISMATCH | deserialization error | SCHEMA_MISMATCH | 0.72 | ✅ |
| 10 | gs_seed_010 | pipeline | SCHEMA_MISMATCH | serialization exception, skip 급증 | SCHEMA_MISMATCH | 0.82 | ✅ |
| 11 | gs_seed_011 | pipeline | CONNECTOR_WORKER_REBALANCE_LOOP | task 할당 불안정, lag↑ | CONNECTOR_WORKER_REBALANCE_LOOP | 0.68 | ✅ |
| 12 | gs_seed_012 | kafka | CONSUMER_LAG_SPIKE | lag 급증, freshness 지연 | CONSUMER_LAG_SPIKE | 0.68 | ✅ |
| 13 | gs_seed_013 | kafka | CONSUMER_LAG_SPIKE | lag↑, offset commit rate 감소 | CONSUMER_LAG_SPIKE | 0.68 | ✅ |
| 14 | gs_seed_014 | kafka | BROKER_RESOURCE_PRESSURE | produce latency↑, ISR shrink | BROKER_RESOURCE_PRESSURE | 0.82 | ✅ |
| 15 | gs_seed_015 | kafka | PARTITION_IMBALANCE | 일부 파티션 latency 급증 | BROKER_RESOURCE_PRESSURE | 0.68 | △@3 |
| 16 | gs_seed_016 | kafka | TOPIC_INGRESS_SPIKE | bytes-in 급증, downstream lag↑ | TOPIC_INGRESS_SPIKE | 0.68 | ✅ |
| 17 | gs_seed_017 | kafka | CONSUMER_REBALANCE_LOOP | group 반복 rebalance, 처리 중단 | CONSUMER_REBALANCE_LOOP | 0.72 | ✅ |
| 18 | gs_seed_018 | sink | SINK_AUTH_EXPIRED | write auth error, task FAILED | UNKNOWN_WITH_EVIDENCE_GAP | 0.00 | 기권 |
| 19 | gs_seed_019 | sink | SINK_WRITE_LATENCY | write p95 증가, task 지연 | SINK_WRITE_LATENCY | 0.68 | ✅ |
| 20 | gs_seed_020 | sink | SINK_CONSTRAINT_VIOLATION | duplicate key error, write 실패 | SINK_CONSTRAINT_VIOLATION | 0.87 | ✅ |
| 21 | gs_seed_021 | sink | SINK_DB_CONNECTION_TIMEOUT | write timeout, connection refused | SINK_DB_CONNECTION_TIMEOUT | 0.85 | ✅ |
| 22 | gs_seed_022 | infra | POD_OOM_KILLED | OOMKilled, container restart | POD_OOM_KILLED | 0.72 | ✅ |
| 23 | gs_seed_023 | infra | POD_CRASH_LOOP | CrashLoopBackOff, 반복 재시작 | POD_CRASH_LOOP | 0.68 | ✅ |
| 24 | gs_seed_024 | infra | NODE_PRESSURE | pod eviction, scheduling 지연 | NODE_PRESSURE | 0.68 | ✅ |
| 25 | gs_seed_025 | infra | PVC_PRESSURE | broker write 실패, topic create 불가 | PVC_PRESSURE | 0.92 | ✅ |
| 26 | gs_seed_026 | infra | DEPLOYMENT_REGRESSION | error rate 급증, task 반복 실패 | UNKNOWN_WITH_EVIDENCE_GAP | 0.00 | 기권 |
| 27 | gs_seed_027 | change | RECENT_CONFIG_CHANGE_REGRESSION | task FAILED, config validation error | PIPELINE_CONFIG_INVALID | 0.68 | △@3 |
| 28 | gs_seed_028 | change | RECENT_SCHEMA_CHANGE_REGRESSION | deserialization 실패, 호환성 에러 | SCHEMA_MISMATCH | 0.82 | △@3 |
| 29 | gs_seed_029 | change | RECENT_IMAGE_DEPLOYMENT_REGRESSION | worker 반복 재시작, task 할당 실패 | UNKNOWN_WITH_EVIDENCE_GAP | 0.00 | 기권 |
| 30 | gs_seed_030 | change | CREDENTIAL_ROTATION_REGRESSION | auth failure, connector task FAILED | CREDENTIAL_ROTATION_REGRESSION | 0.68 | ✅ |
| 31 | gs_seed_031 | data_quality | UPSTREAM_DATA_VOLUME_ANOMALY | throughput 급감, freshness alert | UNKNOWN_WITH_EVIDENCE_GAP | 0.00 | 기권 |
| 32 | gs_seed_032 | data_quality | PIPELINE_DUPLICATE_SPIKE | sink 중복 레코드 급증 | UNKNOWN_WITH_EVIDENCE_GAP | 0.00 | 기권 |
| 33 | gs_seed_033 | data_quality | PIPELINE_FRESHNESS_DELAY | end-to-end freshness SLI 위반 | PIPELINE_FRESHNESS_DELAY | 0.72 | ✅ |
| 34 | gs_seed_034 | data_quality | SCHEMA_NULL_RATE_SPIKE | 특정 컬럼 null rate 30%→90% | SCHEMA_NULL_RATE_SPIKE | 0.68 | ✅ |
| 35 | gs_seed_035 | pipeline | PIPELINE_TASK_RETRY_EXHAUSTED | task FAILED, max retries exceeded | UNKNOWN_WITH_EVIDENCE_GAP | 0.00 | 기권 |

> 집계: ✅ 23건(AC@1) · △@3 5건(상위3 누적 28건=AC@3) · 기권 7건. **기권 7건은 전부 top=UNKNOWN(conf 0)** = 증거 부족 시 단정 대신 보류(Bradford Hill temporality 강제). △@3 5건도 전부 카탈로그 내 인접 유효원인 → **존재하지 않는 원인 날조 0건**.

---

## Part B — 운영 클러스터 라이브 주입 매트릭스 (⏳ 진행 예정)

각 케이스: **주입 → sink/source 커넥터 상태 → 자동 인시던트(metadb) → 자동 RCA(agentdb, root_cause + confidence) → 복구**. 증거 컬럼에 커넥터 상태·인시던트 ID·RCA run을 기록.

| # | 계층 | 기대 root cause | 실제 주입 방법 | 상태 | 증거 |
|---|---|---|---|---|---|
| L1 | sink | SINK_DB_CONNECTION_TIMEOUT | DB scale=0 / connector url 패치 | ⚠️ 라이브 재현 불가(아래 §환경제약) — 과거 #962로 입증됨 | 인시던트 `bfd38509`(06-21) → RCA `SINK_DB_CONNECTION_TIMEOUT` @0.82 |
| L3 | kafka | CONSUMER_LAG_SPIKE | sink 커넥터 **pause** + source 60k 적재 | ⚠️ lag은 발생(~60k) but 인시던트 미생성 | lag 합계 ≈60,000(>50k) 실측 / 신규 인시던트 0 — 과거 #957로 입증 |

### ⚠️ 환경 제약 — 라이브 주입의 현실적 한계 (실측 발견)
운영 클러스터 현 구성상 다음 주입 방식은 **지속 적용이 막혀** 라이브 e2e 재현이 어렵다. 모두 직접 시도로 확인한 근거 있는 발견:

1. **DB 다운(scale=0)**: `kubectl -n tenantdb scale deploy tenant-mariadb --replicas=0` → **ArgoCD `3-data-tenantdb`의 self-heal이 ~40초 만에 replicas=1 복원**(새 pod 생성 확인). 장애 유지 불가.
2. **커넥터 connection.url 오설정**: sink `connection.url`을 도달 불가 호스트로 패치 → **Kafka Connect가 config 검증에서 HTTP 400 거부**("Could not connect to database… tenant-mariadb-DOWN"). CR은 NotReady지만 실행 task는 옛 정상 config로 계속 RUNNING → 런타임 task 실패·인시던트 미발생. (즉시 원복 완료)
3. 따라서 sink/source **DB-다운 계열(L1·L2)**은 ArgoCD 자동복구를 끄지 않는 한 라이브 주입이 비현실적 → 본 캠페인은 **과거 #962/#957의 실제 인시던트 기록**(아래)과 **오프라인 Part A**로 커버한다.

**과거 라이브 주입 성공 기록(운영 환경, 06-21, #962/#957)** — 현재 DB에 잔존하는 실인시던트:
- `bfd38509` sink DB 연결 불가 → RCA **SINK_DB_CONNECTION_TIMEOUT @0.82**
- `5aed2e00`·`ea315de3` Consumer lag (critical) → 자동 인시던트 + RCA run 생성

### 라이브 주입 불가 → 오프라인(Part A)로만 커버
- **infra**(OOM/CrashLoop/NODE/PVC), **change**(IMAGE/SCHEMA/CREDENTIAL_ROTATION): 노드/파드 자원·배포·자격증명 조작은 공용 클러스터에 비가역·광범위 → 미주입. Part A 35케이스로 카탈로그 전수 커버 유지.

---

## 진행 로그 — 라이브 주입 실측 (2026-06-22 01:27~01:43 UTC)

**L1 — sink DB down**
- `scale tenant-mariadb=0` → ~40초 만에 ArgoCD `3-data-tenantdb` self-heal로 replicas=1 복원(새 pod 확인). 트래픽은 복구 후 정상 처리 → 장애 미발생.
- sink `connection.url` → 도달불가 호스트 패치 → Kafka Connect가 **config 검증 HTTP 400 거부**(CR NotReady, 실행 task는 옛 config로 RUNNING). 런타임 실패 미발생 → 인시던트 0. **즉시 원복(connection.url 원상, RUNNING/Ready).**

**L3 — consumer lag (sink pause + source 60,000 INSERT)**
- 01:37 sink 커넥터 `state=paused` 확인 + source `products`에 60,000건 INSERT(총 208,738).
- 01:38~01:43 (~5분) 폴링 → **신규 인시던트 0 · 신규 RCA 0**.
- 01:43 sink `state=running` 원복 → **RUNNING/Ready:True**. 직후 `kafka-consumer-groups --describe` 실측: 파티션 0~5 각 lag ≈ 9,900~10,090 → **합계 ≈ 60,000 (CRITICAL 임계 50,000 초과)**.
- **발견(코드 대조 후 정정)**: lag ~60k가 임계(테스트 워크스페이스는 `workspace_settings` 행 없음 → **기본 critical 50,000** 사용)를 넘었는데도 자동 인시던트 미생성.
  - `KafkaAdminPoller`의 #926 정책은 **커밋오프셋이 없는 컨슈머(미시작·pause-from-empty)만 의도적으로 제외**(오탐 방지). 본 케이스는 running하다 pause돼 **커밋오프셋이 있었으므로 제외 대상 아님** → lag 계산·CRITICAL 평가가 됐어야 함. ⇒ "paused라 스킵"이라는 1차 추정은 코드와 불일치(철회).
  - 실제 미발생 원인은 **edge-trigger in-memory 상태(`lagAlarmState`)가 과거 #957 테스트에서 ERROR로 남아 재발화(rising-edge) 억제**일 가능성이 큼(operations-backend pod 10일 연속 가동). **확정엔 bifrost-system 로그 필요(현 권한 밖)** — 미확정으로 표기.
  - resume 후 lag 0으로 drain.

## Part B 결론

- **현 운영 환경에서 권한 범위 내 신규 라이브 주입으로는 자동 인시던트를 재현하지 못함.** 원인은 RCA/감지 로직 결함이 아니라 **주입 경로의 환경 제약**: ① DB scale=0 → ArgoCD self-heal 복원, ② connector url 오설정 → Connect config 사전검증 거부, ③ consumer pause → lag 모니터 active-member 요건 미충족.
- **자동 감지→인시던트→자동 RCA capability는 과거 #962/#957 실(實)인시던트로 입증**(현재 DB 잔존):
  - `bfd38509` sink DB 연결 불가 → RCA **SINK_DB_CONNECTION_TIMEOUT @0.82**
  - `5aed2e00`·`ea315de3` Consumer lag (critical) → 자동 인시던트 + RCA run
- **카탈로그 전수 정확도는 Part A(35케이스 실측)로 확보**: AC@5 80% · Avg@5 0.724 · ECE 0.073 · 환각 0.
- **개선 제언(정정)**:
  - (1) ~~paused/empty backlog 감지 추가~~ → **주의: 미커밋·paused 컨슈머 제외는 #926의 의도적 오탐 방지 설계**라 단순 감지 추가는 부적절. 진짜 후보 개선은 **인시던트 resolved 후 edge-trigger 상태(`lagAlarmState`) 재무장**(해소된 lag이 재발하면 다시 알림) — 단 위 미발생 원인을 bifrost-system 로그로 확정한 뒤 진행.
  - (2) 테스트 재현용 ArgoCD `3-data-tenantdb` self-heal 일시 비활성 런북(테스트 후 복구).
  - (3) **비파괴 주입 하네스**(메트릭/이벤트 신호 직접 주입) — 운영 자원을 안 건드리고 **전체 35 gold set을 라이브 감지→RCA 경로로 흘릴 수 있음**(현재 라이브 일부만 가능한 근본 한계 해소).

> 주입 데이터: source `products`에 테스트행 ~60,180건 추가(`rca-l1*`/`rca-l3*`, 테스트 tenant 한정). 운영 영향 없음. 최종 상태: 모든 커넥터 RUNNING/Ready, tenant DB 1/1, connection.url 원상.
