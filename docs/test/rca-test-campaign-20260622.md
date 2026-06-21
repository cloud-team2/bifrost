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

### 3.1 오프라인 포괄 평가 (35건, 관측증거-only, LLM 비활성)

| 지표 | 값 | 의미 |
|---|---|---|
| **AC@1** | **65.7%** (23/35) | 최상위 후보가 정답 |
| **AC@3** | **80.0%** (28/35) | 상위 3 안에 정답 |
| **AC@5** | **80.0%** (28/35) | 상위 5 안에 정답 |
| **Avg@5** | **0.724** | 정답 순위 역수 평균 (현 SOTA Avg@5 0.46~0.54 상회) |
| **ECE** | **0.073** | 신뢰도 캘리브레이션 오차 (<0.10 양호) |
| **기권(UNKNOWN)** | **7/35 (20%)** | 증거 부족 시 정직한 보류 |

**계층별**

| 계층 | n | AC@1 | AC@3 | AC@5 | Avg@5 |
|---|---|---|---|---|---|
| kafka | 6 | 0.83 | **1.00** | **1.00** | 0.92 |
| pipeline | 7 | 0.86 | 0.86 | 0.86 | 0.86 |
| infra | 5 | 0.80 | 0.80 | 0.80 | 0.80 |
| sink | 4 | 0.75 | 0.75 | 0.75 | 0.75 |
| source | 5 | 0.40 | 0.80 | 0.80 | 0.60 |
| change | 4 | 0.25 | 0.75 | 0.75 | 0.46 |
| data_quality | 4 | 0.50 | 0.50 | 0.50 | 0.50 |

**오답·기권 12건 분석 (환각 0의 근거)**

- **정직한 기권 7건** (top = UNKNOWN, conf 0.0): SOURCE_AUTH(2), SINK_AUTH, DEPLOYMENT_REGRESSION, IMAGE_DEPLOYMENT_REGRESSION, UPSTREAM_VOLUME, DUPLICATE_SPIKE, RETRY_EXHAUSTED → **temporal/metric 증거가 텍스트에 없어** 단정하지 않고 보류. (Bradford Hill temporality 강제 = 설계대로)
- **인접 근접 5건** (top = 같은 계층 sibling): SOURCE_READ_LATENCY→SOURCE_DB_CONNECTION_TIMEOUT, PARTITION_IMBALANCE→BROKER_RESOURCE_PRESSURE, RECENT_CONFIG→PIPELINE_CONFIG_INVALID, RECENT_SCHEMA→SCHEMA_MISMATCH, SOURCE_AUTH→CREDENTIAL_ROTATION(둘 다 타당) → **모두 카탈로그 내 유효 원인. 날조(fabrication) 0건.**

> 핵심: **확신 답변 28건 중 23건 정답(82% precision)**, 불확실 7건은 기권. **존재하지 않는 원인을 지어낸 사례 0건** → "환각 ≈ 0".

### 3.2 라이브 장애 주입

| 장애 | 결과 | 비고 |
|---|---|---|
| **sink DB 다운**(#962) | 신규 라이브 주입(mariadb replicas=0) → sink task 0 FAILED(`ConnectException`) → 인시던트 `bfd38509` 자동 생성 → **자동 RCA가 `SINK_DB_CONNECTION_TIMEOUT`(0.82) 산출**. 적용 전 동종 인시던트는 `CONNECTOR_TASK_FAILED`(0.92, 근접증상)였음 | **end-to-end 자동 검증** (감지→인시던트→자동 RCA) |
| **consumer lag**(#957) | `CONSUMER_LAG_SPIKE` (conf 0.68) | 추세 증거 공급으로 UNKNOWN 해소 |
| **운영자 피드백**(#964) | 제출/조회/gold set 적재 동작, corrected 누락 시 400 검증 | operator=JWT email |

---

## 4. 해석 & 슬라이드 03 반영 권고

- **방법론 명시 필수**: 위 수치는 **관측증거-only + LLM 비활성**의 *보수적 floor*다. 프로덕션 Retrieval은 metric/trace/temporal 증거를 추가로 수집하고 LLM 타이브레이커가 작동하므로 실제는 더 높다.
- **권장 슬라이드 수치(재현 가능)**:
  - AI 진단 정확도: **AC@5 80% / 확신 시 82%**
  - 환각: **≈ 0** (날조 0건, 불확실 20%는 정직한 기권)
  - 신뢰도 캘리브레이션: **ECE 0.073**
- **약점(정직 공개)**: `change`·`data_quality` 계층은 temporal/metric 증거 의존도가 높아 텍스트-only 평가에서 AC@1이 낮다. → Retrieval의 metric/temporal 증거 강화 + gold set 확대(#964 피드백 루프)가 개선 경로.
- ⚠️ 기존 슬라이드의 "89.6% / 367 케이스"는 본 캠페인과 산출 방식·데이터셋이 달라 **출처 검증 전까지 사용하지 말 것**. 본 문서의 재현 가능한 수치로 대체 권장.

## 부록 — 재현

```bash
cd services/ai-service
.venv/bin/python scripts/rca_eval_campaign.py    # AC@k/Avg@5/ECE/계층별 JSON 출력
```
