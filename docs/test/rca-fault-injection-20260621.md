# RCA·인시던트 장애주입 테스트 보고서

| 항목 | 내용 |
|---|---|
| 일자 | 2026-06-21 |
| 대상 | 운영 클러스터(skala_student) · 워크스페이스 **E2E RCA Test 0621** (`8898903c`) |
| 범위 | 실제 파이프라인에 데이터를 흘려보내며 가역 장애를 주입 → 인시던트 생성·자동 RCA·권장조치·SLO severity·복구 검증 |
| 기준 | `docs/design/rca-standards-review.md` (RCAEval 택소노미 §4.3, SRE 증상기반 알림, SLI/SLO §5.3, spec.md 임계값) |

## 1. 테스트 환경
- **CDC 파이프라인** `cdc-products` (`5d4b0826`): tenant-postgres `public.products` → Debezium → Kafka → JDBC sink → tenant-mariadb `testdb`. (full e2e)
- **EDA 파이프라인** `eda-customers` (`d9d9497b`): tenant-postgres `public.customers` → Kafka (fan-out, sink 없음).
- 데이터 생성기: postgres 에 products 10행 + customers 2행 / 3초 연속 insert (테스트 중 가동).
- 베이스라인: OPEN 인시던트 0, CDC e2e 동기화(postgres=mariadb), 커넥터 전부 RUNNING.

## 2. 주입 시나리오와 결과

### 주입#1 — Sink DB 연결 차단 (network 계열)
- **주입**: `tenant-mariadb` 배포 replicas=0 (14:20:59Z).
- **감지**: ~30초 후 인시던트 `7f328a7d` CRITICAL "sink DB 'tenant-mariadb' 연결 불가" (DB 헬스 프로브, #918).
- **severity**: `impact=user_sli:data_completeness; urgency=page; slo_burn_rate=393.94` — SLI/SLO burn-rate severity 정상 동작. ✓
- **자동 RCA**: 인시던트 생성 즉시 발화 → `UNKNOWN_WITH_EVIDENCE_GAP`(0.0). 당시 커넥터 task 아직 RUNNING(실패 증거 없음). 약 2분 뒤 task FAILED 후 재분석 → `CONNECTOR_TASK_FAILED`(0.92).
- **복구**: mariadb 복원만으론 FAILED task 미복구 → 커넥터 재시작 후 sink 즉시 캐치업(lag 0, **데이터 손실 0**). 파이프라인 active.
- 인시던트는 OPEN 유지(B.7: CRITICAL 자동 resolve 안 함 — 설계 의도).

### 주입#2 — Consumer lag 급증 (throughput 계열)
- **주입**: sink 커넥터 pause + source 6만행 버스트 (14:31:55Z) → consumer lag 6만+.
- **감지**: ~40초 후 인시던트 `ea315de3` CRITICAL "Consumer lag critical" (#926 라이브 재확인). severityReason slo_burn_rate=967. ✓
- **자동 RCA**: `UNKNOWN_WITH_EVIDENCE_GAP`(0.0) — 단, Classifier 는 `CONSUMER_LAG_SPIKE`를 **0.90으로 정확히 분류**했고 evidence 에 **`consumer lag snapshot: total_lag=60110`** 도 수집됨. 즉 분류·증거가 충분한데도 RCA 가 UNKNOWN. (→ §4 핵심 결함)
- **복구**: 커넥터 resume → backlog 즉시 드레인(lag 0).

### 주입#3 (커넥터 task 실패) / #4 (스키마 비호환) — 보류
- #3 은 주입#1의 커넥터 실패 경로(CONNECTOR_TASK_FAILED, 재분석 0.92로 검증)와 동일 패턴이라 별도 주입 생략.
- #4(스키마 비호환)는 CDC 재-snapshot 리스크가 있어 라이브 파이프라인 보호 위해 보류. 필요 시 별도 진행.

## 3. 정상 동작 확인 (✓)
- 인시던트 생성: sink 연결 불가 ~30초, consumer lag ~40초 빠른 감지.
- **SLO/SLI severity**: 두 인시던트 모두 `impact/urgency/slo_burn_rate` 기반 severityReason 산출(원인 임계값이 아니라 사용자 영향 SLI 기준 — 문서 §5 방향과 일치).
- **#926** consumer lag 인시던트 생성 라이브 재확인.
- **무손실 복구**: 장애 해소 후 sink 가 마지막 커밋 오프셋부터 재개(at-least-once), 데이터 손실 0.
- 자동 RCA 트리거(#923)·"분석 중" 표시(#935)·권장조치 노출 경로 동작.

## 4. 핵심 결함 — 자동 RCA가 분류 확신·증거 존재에도 UNKNOWN (→ #957)
- **증상**: Classifier `CONSUMER_LAG_SPIKE` 0.90 + evidence `total_lag=60110` 가 있는데도 RCA 가 `required_evidence_satisfied=False` → `UNKNOWN_WITH_EVIDENCE_GAP`(0.0).
- **원인(추정)**: `agents/rca.py` 의 required-증거 충족 판정이 lexical + semantic(임베딩 threshold 0.86) 매칭에 의존. semantic 켜짐(`AI_RCA_EMBEDDING_MATCH_ENABLED=true`)·임베더 실패 로그 없음에도, **한글 룰("consumer lag 급증") ↔ 영어/수치 증거("total_lag=60110") 매칭이 임계값을 못 넘김**.
- **영향**: 명백한 장애도 자동 RCA 결과가 "원인 불명"으로 보임. 사용자 신뢰·자동화 가치 저하.
- **주의**: threshold 를 blind 하게 낮추면 오탐 위험. 문서 §7(roadmap 6~8)·§8 은 gold set + AC@k + ECE 평가셋으로 보정하라고 명시.

## 5. 부가 발견
- **[A] 자동 RCA 조기 발화**: 인시던트 생성 즉시 발화 → 증거가 늦게 쌓이는 장애(sink DB down)에선 첫 결과가 UNKNOWN. UNKNOWN 시 증거 누적 후 재시도/지연 로직 없음.
- **[B] 원인 깊이**: sink DB down 을 CONNECTOR_TASK_FAILED(근접 증상)까지만 짚고 SINK_DB_CONNECTION_TIMEOUT(진짜 원인)엔 미도달 — DB 헬스 프로브의 datasource 불가 신호가 RCA 증거로 연결되지 않음.
- **[C] 자동 복구/롤백 부재**: 조치 실패(FAILED task) 후 자동 재시작·롤백 없음 → 수동 재시작 필요(문서 §Q2 기지 갭 실증).

## 6. 권장 (우선순위)
1. **#957 RCA evidence-satisfaction** — (단기) 매칭 실패 진단 로그 + 명백한 lexical anchor 보강 + 회귀 테스트. (중기) semantic threshold 캘리브레이션 + UNKNOWN 시 재분석 트리거([A]). 평가셋(gold set) 동반.
2. **[B] 인시던트 신호의 RCA 증거화** — DB 헬스 프로브/lag 모니터의 감지 사유를 RCA evidence 로 주입해 root cause 정확도 향상.
3. **[C] 자동 복구** — 문서 §7 단계5(자동 롤백/재시작) 로드맵.

## 부록 — 복구 상태
테스트 종료 시점: 두 파이프라인 active, 커넥터 전부 RUNNING, CDC sink 동기화(lag 0). 데이터 생성기 중지. 인시던트 7f328a7d·ea315de3 는 OPEN 유지(CRITICAL, B.7 설계).
