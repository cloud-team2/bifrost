# RCA 장애 대응 테스트 캠페인 (2026-06-22)

> 발표 03(AI 성능지표)의 수치 근거. 좁은 단일 장애가 아니라 **전 계층(8계층 35 root cause)을 포괄**하는 평가 + 대표 **라이브 장애 주입**으로 측정했다.
> 재현 스크립트: [services/ai-service/scripts/rca_eval_campaign.py](../../services/ai-service/scripts/rca_eval_campaign.py)
> 평가 대상: develop = 배포 이미지 `4a7ca906` (#962/#964 포함).

---

## 1. 장애 분류 체계 (어떤 장애들로 나눴나)

RCA 근본원인 카탈로그를 **8계층 35개**로 정의하고, 33개 인시던트 유형 → root cause 후보로 매핑한다.

| 계층 | root cause 수 | 대표 예 |
|---|---|---|
| source | 5 | SOURCE_AUTH_EXPIRED, SOURCE_NETWORK_REACHABILITY, SOURCE_DB_CONNECTION_TIMEOUT, SOURCE_READ_LATENCY, SOURCE_DATA_NOT_READY |
| pipeline | 5 | CONNECTOR_TASK_FAILED, SCHEMA_MISMATCH, PIPELINE_CONFIG_INVALID, PIPELINE_TASK_RETRY_EXHAUSTED, CONNECTOR_WORKER_REBALANCE_LOOP |
| kafka | 5 | CONSUMER_LAG_SPIKE, BROKER_RESOURCE_PRESSURE, PARTITION_IMBALANCE, TOPIC_INGRESS_SPIKE, CONSUMER_REBALANCE_LOOP |
| sink | 4 | SINK_DB_CONNECTION_TIMEOUT, SINK_AUTH_EXPIRED, SINK_WRITE_LATENCY, SINK_CONSTRAINT_VIOLATION |
| infra | 5 | POD_OOM_KILLED, POD_CRASH_LOOP, NODE_PRESSURE, PVC_PRESSURE, DEPLOYMENT_REGRESSION |
| change | 4 | RECENT_CONFIG/SCHEMA/IMAGE_CHANGE_REGRESSION, CREDENTIAL_ROTATION_REGRESSION |
| data_quality | 4 | UPSTREAM_DATA_VOLUME_ANOMALY, PIPELINE_DUPLICATE_SPIKE, PIPELINE_FRESHNESS_DELAY, SCHEMA_NULL_RATE_SPIKE |
| unknown | 3 | UNKNOWN_WITH_EVIDENCE_GAP, MULTIPLE_POSSIBLE_CAUSES, CUSTOMER_OWNED_ROOT_CAUSE_LIKELY |

**외부 표준 대비**: RCAEval 벤치마크 fault 11종(resource 4 / network 2 / code-level 5)을 포괄하며, `data_quality` 계층은 RCAEval에 없는 데이터 파이프라인 도메인 고유 강점. (근거: [rca-standards-review.md §4.3](../design/rca-standards-review.md))

---

## 2. 테스트 방법 (어떻게 진행했나)

### 2.1 오프라인 포괄 평가 — 전 계층 커버
- **대상**: gold set 35건(계층별 대표 시나리오, `app/evaluation/seed_gold_set.py`).
- **입력 증거**: 각 시나리오의 **관측 신호만**(symptom + trigger + contributing_factors). 사후 결론인 `human_verdict`는 **정답 누출 방지를 위해 제외**.
- **중요 조건**: 캠페인 스크립트는 accepted root cause에서 incident type을 역매핑해 RCA 후보 풀을 구성하는 **oracle incident-type RCA replay**다. classifier 포함 end-to-end 정확도나 unseen production holdout으로 해석하지 않는다.
- **조건**: LLM 타이브레이커 **비활성** → 카탈로그 + 증거 매트릭스 + 신뢰도 게이트만으로 판정(가장 보수적 floor).
- **지표**: AC@1/AC@3/AC@5, Avg@5(RCAEval 표준), ECE(Guo et al. 캘리브레이션), 기권율.
- **실행**: `cd services/ai-service && .venv/bin/python scripts/rca_eval_campaign.py`

### 2.2 라이브 장애 주입 — 대표 end-to-end 검증
실제 운영 환경(skala_student, 파이프라인 `e2e-rca-test-0621`)에 장애를 주입해 **인시던트 자동 생성 → 자동 RCA → 결과**의 전 구간을 검증.

| 장애 | 주입 방법 | 검증 포인트 |
|---|---|---|
| **sink DB 다운**(#962) | `kubectl -n tenantdb scale deploy tenant-mariadb --replicas=0` + 데이터 생성기 가동 | sink task FAILED + connection refused → RCA top = SINK_DB_* (근접증상 강등) |
| **consumer lag 급증**(#957) | 생성기 대량 적재 → lag 임계 초과 | RCA = CONSUMER_LAG_SPIKE (추세 증거) |
| **운영자 피드백**(#964) | API 제출(맞음/아님/수정) | gold set 적재 + 검증(400) |

> 데이터 생성기: source DB(`testdb.public.products`)에 3초마다 insert/update/delete → CDC→Kafka→sink 트래픽. (트레이스/지표 관측용)

---

## 3. 결과 수치 (어떻게 나왔나)

### 3.1 오프라인 포괄 평가 재측정 (35 seed, oracle incident-type RCA replay)

2026-06-22 #993 수정 후 현재 브랜치에서 `services/ai-service/scripts/rca_eval_campaign.py`를 재실행한 값이다. 입력은 seed의 `symptom`, `trigger`, `contributing_factors`만 사용하고 `human_verdict`는 제외한다. 단, 스크립트가 accepted root cause에서 incident type을 역매핑하므로 classifier 포함 end-to-end 정확도나 unseen production holdout으로 해석하지 않는다.

| 지표 | 값 | 의미 |
|---|---|---|
| **AC@1** | **97.14%** (34/35) | 최상위 후보가 정답 |
| **AC@3** | **100.0%** (35/35) | 상위 3 안에 정답 |
| **AC@5** | **100.0%** (35/35) | 상위 5 안에 정답 |
| **Avg@5** | **0.9857** | 정답 순위 역수 평균 |
| **ECE** | **0.1284** | 0.90 cap 결과 0.1448보다 완화됐지만 baseline floor보다 높음 |
| **기권(UNKNOWN)** | **0/35 (0%)** | seed replay 조건의 기권 수 |

**계층별**

| 계층 | n | AC@1 | AC@3 | AC@5 | Avg@5 |
|---|---|---|---|---|---|
| kafka | 6 | 1.00 | 1.00 | 1.00 | 1.00 |
| pipeline | 7 | 1.00 | 1.00 | 1.00 | 1.00 |
| infra | 5 | 1.00 | 1.00 | 1.00 | 1.00 |
| sink | 4 | 1.00 | 1.00 | 1.00 | 1.00 |
| source | 5 | 0.80 | 1.00 | 1.00 | 0.90 |
| change | 4 | 1.00 | 1.00 | 1.00 | 1.00 |
| data_quality | 4 | 1.00 | 1.00 | 1.00 | 1.00 |

**오답 분석**

- 오답 1건: `SOURCE_READ_LATENCY` seed가 `SOURCE_DB_CONNECTION_TIMEOUT`을 top으로 냄
- 해석 제한: replay 조건상 incident type은 oracle로 주입되며, 100%에 가까운 값은 production unseen 일반화 수치가 아님

### 3.1.1 보존 floor (develop 기준 기존 발표 수치)

기존 develop 배포 이미지 `4a7ca906` 기준 보수적 floor는 본문 재측정값과 함께 인용해야 한다. 문서 본문의 과거 수치는 AC@1 65.7%, AC@3 80.0%, AC@5 80.0%, Avg@5 0.724, ECE 0.073, 기권 7/35였고, 보존 JSON `docs/test/results-20260622/rca_campaign_floor.json`은 AC@1 71.43%, AC@3 85.71%, AC@5 85.71%, Avg@5 0.781, ECE 0.0832, 기권 5/35를 기록한다.

> 핵심: 현재 branch replay 수치와 보존 floor는 산출 시점과 코드 상태가 다르다. 100% 또는 97.14% 값을 단독으로 쓰지 말고 oracle incident-type, seed 내부 replay, floor 범위를 함께 표시한다.

### 3.2 라이브 장애 주입

| 장애 | 결과 | 비고 |
|---|---|---|
| **sink DB 다운**(#962) | 신규 라이브 주입(mariadb replicas=0) → sink task 0 FAILED(`ConnectException`) → 인시던트 `bfd38509` 자동 생성 → **자동 RCA가 `SINK_DB_CONNECTION_TIMEOUT`(0.82) 산출**. 적용 전 동종 인시던트는 `CONNECTOR_TASK_FAILED`(0.92, 근접증상)였음 | **end-to-end 자동 검증** (감지→인시던트→자동 RCA) |
| **consumer lag**(#957) | `CONSUMER_LAG_SPIKE` (conf 0.68) | 추세 증거 공급으로 UNKNOWN 해소 |
| **운영자 피드백**(#964) | 제출/조회/gold set 적재 동작, corrected 누락 시 400 검증 | operator=JWT email |

---

## 4. 해석 & 슬라이드 03 반영 권고

- **방법론 명시 필수**: 현재 재측정 수치는 **관측증거-only + LLM 비활성 + oracle incident-type**의 seed replay 결과다. 프로덕션 Retrieval은 metric/trace/temporal 증거를 추가 수집할 수 있지만 classifier 포함 end-to-end 및 unseen production holdout은 별도 측정이 필요하다.
- **해석 제한**: 97.14% 또는 100% 계열 값은 기존 35 seed의 조건부 replay 결과이며 본 문서의 floor(AC@1 65.7%, 보존 JSON 기준 71.43%)와 함께 표시해야 한다.
- **권장 슬라이드 수치(재현 가능)**:
  - AI 진단 정확도: **보존 floor AC@1 65.7~71.4%, 현재 oracle replay AC@1 97.14%**
  - 환각: **≈ 0** (날조 0건, 불확실 20%는 정직한 기권)
  - 신뢰도 캘리브레이션: **floor ECE 0.073~0.0832, 현재 replay ECE 0.1284**
- **약점(정직 공개)**: `change`·`data_quality` 계층은 temporal/metric 증거 의존도가 높아 텍스트-only 평가에서 AC@1이 낮다. → Retrieval의 metric/temporal 증거 강화 + gold set 확대(#964 피드백 루프)가 개선 경로.
- ⚠️ 기존 슬라이드의 "89.6% / 367 케이스"는 본 캠페인과 산출 방식·데이터셋이 달라 **출처 검증 전까지 사용하지 말 것**. 본 문서의 재현 가능한 수치로 대체 권장.

## 부록 — 재현

```bash
cd services/ai-service
.venv/bin/python scripts/rca_eval_campaign.py    # AC@k/Avg@5/ECE/계층별 JSON 출력
```
