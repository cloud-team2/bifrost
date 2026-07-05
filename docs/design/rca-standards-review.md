# RCA·장애대응 에이전트 표준 검토 요약

> 이 문서는 긴 외부 조사 원문을 보관하는 곳이 아니라, Bifrost RCA/장애대응 문서들이 참조하는 표준 근거와 코드 기준 gap을 요약하는 라우팅 문서다.
>
> SOT는 코드 구현이다. 구현 여부는 `services/ai-service`, `services/operations-backend`, migration, 테스트를 기준으로 다시 확인한다.

## 이 문서의 용도

- FastAPI RCA/Agent 설계 문서가 참조하는 공통 근거를 한 곳에 둔다.
- 발표·리뷰에서 사용할 수 있는 기준 문장과 금지 문장을 분리한다.
- 이미 구현된 항목과 아직 운영 검증이 필요한 항목을 구분한다.

## 읽기 전 요약

현재 Bifrost RCA는 "로그를 LLM에 던져서 원인을 맞히는 구조"가 아니다. 고정 root cause catalog, evidence matrix, confidence scoring, UNKNOWN fallback, Verifier, 정책·승인 gate로 LLM 판단을 제한한다.

다만 운영 수준 완성도는 별도 문제다. 자연어 질의 depth 제어, run telemetry, reproducibility manifest, threshold registry, SLO burn-rate routing, rollback, KEDB, feedback/drift 기반은 들어왔지만, baseline 보정·정기 평가·dashboard·runtime registry 연결·Verifier 실패 후 rollback 일반화는 남아 있다.

## 목차

1. [핵심 결론](#1-핵심-결론)
2. [현재 아키텍처](#2-현재-아키텍처)
3. [리뷰어 도전 질문](#3-리뷰어-도전-질문)
4. [RCA 판별 기준](#4-rca-판별-기준)
5. [표준 지표를 Bifrost에 적용하는 방식](#5-표준-지표를-bifrost에-적용하는-방식)
6. [자연어 질의 Agent 호출량 제어](#6-자연어-질의-agent-호출량-제어)
7. [개선 로드맵](#7-개선-로드맵)
8. [후속 과제](#8-후속-과제)
9. [인용 시 주의](#9-인용-시-주의)
10. [주요 출처](#10-주요-출처)

## 1. 핵심 결론

### 1.1 한 줄 판정

표준 관점에서 안전한 RCA는 LLM 단독 추론이 아니라 **증거 기반 판단, 보류(abstain), 재현성, 운영자 승인, 감사 가능성**을 갖춘 제한형 workflow다.

Bifrost는 이 방향을 따른다. `run_rca()`는 catalog/evidence matrix를 기준으로 후보를 만들고, 근거가 부족하면 `UNKNOWN_WITH_EVIDENCE_GAP`로 보류한다. Remediation과 Executor는 정책·승인·변경 gate를 거치며, mutation은 audit/idempotency/rollback 정보를 남긴다.

### 1.2 표준 대비 현황

| 구분 | 현재 상태 |
|---|---|
| 충족 | evidence 기반 RCA, UNKNOWN fallback, 정책·승인 gate, append-only state patch, audit event, catalog/run manifest 저장 |
| 부분 구현 | rollback, run telemetry, threshold registry, online feedback/drift, KEDB, SLO burn-rate routing |
| 남은 gap | resolved incident 기반 정기 평가, ECE/AC@k calibration 운영화, dashboard, runtime threshold registry 연결, baseline SLO 확정, Verifier 실패 후 runbook rollback 일반화 |

### 1.3 우선 개선 항목

1. 자연어 질의가 필요한 agent/tool만 호출하는지 회귀 테스트로 고정한다.
2. run telemetry의 tool/LLM/handoff instrumentation과 cost/budget 집계를 완성한다.
3. RCA threshold와 Spring 운영 threshold를 registry source of truth로 연결한다.
4. resolved incident 기반 AC@k/ECE 평가와 UNKNOWN threshold 재보정을 운영화한다.
5. SLO burn-rate routing의 baseline, fallback, low-traffic 보정을 검증한다.

## 2. 현재 아키텍처

### 2.1 RCA 파이프라인

```text
Router
  -> Planner
  -> Retrieval
  -> Classifier
  -> RCA
  -> Remediation
  -> Policy/Approval/Change Gate
  -> Executor
  -> Verifier
  -> Report
```

주요 코드 기준:

| 영역 | 코드 |
|---|---|
| RCA | [services/ai-service/app/agents/rca.py](../../services/ai-service/app/agents/rca.py) |
| Agent transition | [services/ai-service/app/supervisor/transitions.py](../../services/ai-service/app/supervisor/transitions.py) |
| Evidence matrix | [services/ai-service/app/catalogs/evidence_matrix.py](../../services/ai-service/app/catalogs/evidence_matrix.py) |
| Root cause catalog | [services/ai-service/app/catalogs/root_causes.py](../../services/ai-service/app/catalogs/root_causes.py) |
| Workflow runner | [services/ai-service/app/workflow/runner.py](../../services/ai-service/app/workflow/runner.py) |
| Incident/SLO routing | [services/operations-backend/src/main/java/com/bifrost/ops/incident/IncidentService.java](../../services/operations-backend/src/main/java/com/bifrost/ops/incident/IncidentService.java) |

### 2.2 조치(Remediation)와 안전장치

- Remediation은 runbook 기반 조치 후보를 제안한다.
- Executor는 `READY` 상태만 실행하고 idempotency key와 audit event를 남긴다.
- `low/read-only`는 자동 실행 가능하지만, `medium` 이상은 정책과 승인 조건을 따른다.
- `workflow/stages/rollback.py`는 실패/BLOCKED mutation에 대해 inverse tool 기반 rollback을 시도한다.
- 남은 gap은 Verifier 실패 후 runbook `rollback_plan` 실행과 saga/보상 트랜잭션 일반화다.

### 2.3 버전 관리

| 항목 | 코드 기준 상태 | 남은 gap |
|---|---|---|
| Catalog version | `core/config.py`의 `catalog_version`, run record 저장, health 노출 | 운영 절차 문서화 |
| Run reproducibility | migration `013_create_run_reproducibility.py`와 manifest 저장 | 실제 LLM 호출 모델 alias를 snapshot/provider revision으로 강제하는지 검증 |
| Model routing | `llm/model_router.py`의 tier mapping과 snapshot mapping | 과거 run 재현 리허설 |
| Corpus manifest | `corpus/manifest.json`과 manifest hash 저장 | baseline/version 운영 절차 |

### 2.4 장애 알림 기준

Spring `IncidentService`는 SLO burn-rate decision을 우선 적용한다. 사용자 영향이 있으면 `PAGE/CRITICAL` 또는 `TICKET/WARNING`으로 라우팅하고, `severity_reason`, `alert_route`를 저장한다. SLO 측정값이 없으면 static threshold fallback을 사용한다.

남은 gap은 Prometheus 비활성·측정 불가 fallback이 page noise를 만들지 않는지 검증하고, 서비스 baseline으로 objective와 low-traffic 보정 기준을 확정하는 것이다.

### 2.5 코드 기준 미구현·부분 구현 목록

| 영역 | 현재 구현 | 남은 gap |
|---|---|---|
| 자연어 질의 depth 제어 | `execution_depth`, `max_tool_calls`, `allow_react_loop`, `history_policy`; lookup depth는 Verifier 제외 | Router 휴리스틱과 대표 질의별 호출량 테스트 |
| Agent 실행 관측성 | `run_telemetry` schema/collector, stage latency, called_agents | tool/LLM/handoff instrumentation, cost/budget 집계 |
| Run 재현성 | model/prompt/catalog/evidence/runbook/corpus/eval/code manifest 저장 | snapshot 모델 강제와 재현 리허설 |
| 자동 rollback | 실패/BLOCKED mutation inverse rollback | Verifier 실패 후 runbook rollback, saga 일반화 |
| Threshold governance | threshold registry/API와 초기값 | 모든 runtime threshold의 source of truth 연결 |
| Gold set/labeling | schema/API, labeling guide, feedback 승격 경로 | 운영 UI, inter-review consistency, 정기 eval job |
| AC@k/ECE | eval/calibration 모듈과 script 기반 | resolved incident 기반 monthly artifact |
| Online drift | feedback event와 drift report | dashboard, 운영 기준, 재보정 trigger |
| 사용자 영향 SLI/SLO | SLI 정의·측정 API, burn-rate routing | baseline 확정, fallback/low-traffic 검증 |
| 인과/상관 태그 | `EvidenceRule` 필드와 temporality required 강등 | 전체 profile 일관성, 평가 fixture 보강 |
| KEDB | schema/API/repository, static report surface | RCA 결과와 재발·owner·verified fix 자동 누적 |

## 3. 리뷰어 도전 질문

### Q1. 환각 통제: "로그만 던지면 RCA가 되나?"

아니다. Bifrost는 raw log를 LLM에 통째로 넣는 방식이 아니라, 운영 도구와 catalog/evidence matrix로 근거를 구조화한 뒤 후보를 제한한다. 근거가 부족하면 `UNKNOWN_WITH_EVIDENCE_GAP`를 반환하고 필요한 추가 증거를 남긴다.

### Q2. 롤백 / 실패 시나리오

현재 rollback은 executor 결과가 FAILED/BLOCKED인 mutation을 대상으로 inverse tool을 실행하는 구조다. risk가 높은 원조치의 rollback은 승인 대기로 남긴다. Verifier가 "성공 조건 미달"을 판단한 뒤 runbook rollback을 실행하는 일반화는 아직 후속 과제다.

### Q3. 버전 관리

Run manifest는 모델, 프롬프트, catalog/evidence/runbook/corpus/eval/code 정보를 저장한다. 다만 실제 LLM 호출이 alias가 아니라 snapshot/provider revision으로 고정되는지와 과거 run 재현 리허설은 별도 검증이 필요하다.

### Q4. 장애 알림 기준

Page는 원인 지표 하나가 튀었다고 바로 보내는 것이 아니라 사용자 영향 SLO burn-rate 위반을 중심으로 보낸다. Consumer lag, connector FAILED, replication lag 같은 원인 지표는 SLO 영향이 없으면 diagnostic/RCA evidence로 낮춘다. 측정 불가 시 static fallback은 유지하되 noise 검증이 필요하다.

## 4. RCA 판별 기준

### 4.1 방법론

RCA 판단은 세 단계를 지킨다.

1. 증거가 catalog의 required/supporting/negative rule을 만족하는지 확인한다.
2. 시간 선행성, causal chain, negative evidence를 반영해 confidence를 제한한다.
3. 기준 미달이면 UNKNOWN으로 보류하고 추가 수집할 증거를 명시한다.

### 4.2 인과주장 검증 기준: 상관 ≠ 인과

인과 주장은 "같은 시간에 관측됐다"만으로 충분하지 않다. 최소한 원인 후보가 증상보다 먼저 발생했는지, 같은 causal chain에 있는지, 반대 증거가 없는지 확인한다.

Bifrost 적용:

- `EvidenceRule.causality_type`, `temporality_required`, `causal_chain_step`으로 증거 성격을 태그한다.
- 시간 선행성이 없는 `temporality_required` required match는 supporting 수준으로 낮춘다.
- 남은 작업은 전체 evidence profile의 태그 일관성과 평가 fixture 확장이다.

### 4.3 택소노미

Trigger, symptom, root cause, contributing factor를 분리한다. 최근 배포나 설정 변경은 trigger일 수 있지만, 그 자체가 항상 root cause는 아니다. Resolved incident 평가에서는 `accepted_root_cause_id`와 trigger를 분리 저장해야 AC@k/ECE가 왜곡되지 않는다.

### 4.4 증거·신뢰도·평가

평가는 단일 정답 hit만 보지 않는다.

| 지표 | 용도 |
|---|---|
| AC@1/3/5 | 정답 root cause가 상위 후보에 들어오는지 측정 |
| Avg@5 | 상위 후보 품질을 평균적으로 확인 |
| ECE | confidence가 실제 정확도와 맞는지 확인 |
| UNKNOWN ratio | 무리한 확정 대신 안전하게 보류하는지 확인 |

## 5. 표준 지표를 Bifrost에 적용하는 방식

### 5.1 적용 원칙

외부 표준은 방향과 검증 방식을 제공한다. Bifrost의 임계값, SLO objective, UNKNOWN threshold는 자체 운영 데이터로 보정한다.

### 5.2 개선 항목별 외부 근거와 Bifrost 적용

| 개선 항목 | 참고 기준 | Bifrost 적용 |
|---|---|---|
| 자동 rollback | AWS Well-Architected OPS06-BP04, Argo Rollouts AnalysisRun | 실패/BLOCKED mutation inverse rollback은 구현. Verifier 실패 후 runbook rollback은 보강 |
| Run 재현성 | NIST AI RMF, ISO/IEC 42001, MLflow, Langfuse | run manifest 저장은 구현. snapshot 모델 강제와 재현 리허설은 보강 |
| Confidence calibration | ECE, PACE-LM | calibration module은 있음. resolved incident monthly report는 보강 |
| AC@k 평가 | RCAEval | 평가 지표 방향은 확정. 운영 resolved incident 기반 정기 job은 보강 |
| Abstention | LLM abstention survey, Self-RAG | `UNKNOWN_WITH_EVIDENCE_GAP` 유지. threshold 조정은 데이터 기반으로 수행 |
| 사용자 영향 SLI/SLO | Google SRE, Datadog burn-rate | SLI 측정·routing은 구현. baseline과 fallback 검증은 보강 |
| Severity/routing | PagerDuty, ITIL problem management | `severity_reason`, `alert_route` 저장. impact x urgency 기준 보정 필요 |

### 5.3 Bifrost용 후보 SLI/SLO 정의

| SLI | good event 예시 |
|---|---|
| 데이터 신선도 | source event가 objective 안에 sink에 반영됨 |
| End-to-end latency | source timestamp부터 sink commit까지 objective 이하 |
| 처리 성공률 | 정상 처리·적재 성공 |
| 데이터 완전성 | 기대 row/event 수와 sink 반영 수가 허용 오차 내 일치 |
| Provisioning 성공률 | pipeline 생성 요청이 허용 시간 내 성공 상태로 전환 |

### 5.4 page/ticket/diagnostic 라우팅 기준

| 신호 | 현재 처리 |
|---|---|
| SLO burn-rate page 조건 | `CRITICAL` incident + page route |
| SLO burn-rate ticket 조건 | `WARNING` incident + ticket route |
| Consumer lag / connector FAILED / replication lag | 사용자 영향이 있으면 page/ticket, 없으면 diagnostic/RCA evidence |
| 측정 불가 | static threshold fallback. noise 검증 필요 |

### 5.5 구현·발표 반영 문장

발표에서는 "임의 기준"이라고 말하지 않는다. 자동 rollback은 failure-condition 기반 rollback 패턴, 재현성은 run lineage/versioning, RCA 평가는 AC@k/ECE, 알림은 SLO burn-rate와 impact/urgency 기준으로 설명한다. 단, 구체 threshold와 SLO objective는 Bifrost 운영 데이터로 캘리브레이션한다고 말한다.

## 6. 자연어 질의 Agent 호출량 제어

### 6.1 문제 정의

"상태 한번 봐줘", "lag 확인해줘" 같은 단순 질의가 incident analysis 전체 workflow를 타면 agent/tool 호출이 과도해진다. 문제는 LLM이 아니라 workflow가 의도를 깊이별로 제한하는지다.

### 6.2 현재 코드 기준 상태

| 항목 | 현재 상태 |
|---|---|
| Depth-aware transition | `simple_query` lookup depth는 `planner -> retrieval -> report`로 끝나고 Verifier를 건너뛴다 |
| Router budget | Router가 `execution_depth`, `max_tool_calls`, `allow_react_loop`, `history_policy`를 반환한다 |
| Retrieval budget | `max_tool_calls=0`이면 운영 tool 호출을 건너뛰고, `allow_react_loop`가 꺼져 있으면 ReAct를 돌리지 않는다 |
| 남은 gap | Router 오분류, 대표 질의별 호출량 회귀 테스트, history policy 품질 검증 |

### 6.3 외부 기준에서 본 올바른 방향

Handoff, guardrail, tool-use, termination, ReAct 기준은 같은 방향을 가리킨다. 필요한 specialist만 호출하고, 비싼 tool/model 실행 전에 scope를 줄이며, multi-agent run에는 명시적인 종료 조건과 예산이 있어야 한다.

### 6.4 해결 방향과 현재 구현 상태

#### 6.4.1 Router 출력의 `execution_depth`

| Depth | 의미 | Tool budget |
|---|---|---|
| `direct_answer` | 운영 tool 없이 답변 | 0 |
| `single_lookup` | read-only tool 1개 | 1 |
| `bounded_lookup` | read-only tool 2개까지 | 2 |
| `incident_diagnosis` | Classifier/RCA 포함 | 6, ReAct 허용 |
| `remediation_planning` | RCA 뒤 조치 후보·gate 포함 | 6, ReAct 허용 |
| `action_execution` | 승인/변경/실행 경로 | read-only 0, mutation은 governance 경로 |

#### 6.4.2 `simple_query` lookup 경로

```text
simple_query.direct_answer
  -> planner
  -> retrieval(max_tool_calls=0)
  -> report

simple_query.single_lookup / bounded_lookup
  -> planner
  -> retrieval(max_tool_calls 제한)
  -> report
```

Verifier는 단순 조회에는 넣지 않는다. 운영 변경, RCA 결론, 사용자 영향 판단처럼 검증 가치가 큰 출력에 유지한다.

#### 6.4.3 Planner prompt의 "최소 충분 조회" 원칙

기본은 가장 좁은 tool 1개다. 여러 tool은 사용자가 원인 분석, 상관관계, 상세 진단을 요청했거나 단일 tool로 답변할 수 없을 때만 선택한다. LLM 출력 이후에도 코드가 tool 수 상한을 다시 적용해야 한다.

#### 6.4.4 ReAct 루프를 조건부로만 켠다

ReAct는 chaining이 필요한 incident/remediation depth에서만 켠다. `single_lookup`, `bounded_lookup`에서는 flat plan과 capped tool budget으로 끝낸다.

#### 6.4.5 대화 히스토리 input filter

Router/Planner에는 전체 대화 원문보다 최근 발화, 짧은 summary, 추출된 intent/entity/depth만 전달한다. Retrieval/ReAct에는 필요한 tool input만 전달하고, Report만 사용자 친화성을 위해 요약 history를 볼 수 있게 한다.

### 6.5 현재 agent 구조 검증

큰 구조는 맞다. Router, Planner, Retrieval, Classifier, RCA, Remediation, Verifier, Report 역할 분리는 유지한다. Policy Guard, Executor, Approval Gate, Change Gate를 LLM 밖의 결정론적 단계로 둔 것도 맞다.

남은 위험은 Router 휴리스틱과 호출량 회귀 검증이다. 특히 `simple_query.direct_answer`도 stage상 planner/retrieval/report를 지나므로, no-stage fast path가 필요한지는 별도 판단한다.

### 6.6 코드 개선 체크리스트

| 우선순위 | 개선 | 완료 기준 |
|---|---|---|
| 높음 | 대표 자연어 질의별 depth/tool count 테스트 | 단순 조회가 classifier/rca/verifier를 호출하지 않음 |
| 높음 | tool/LLM/handoff instrumentation | run_id로 호출량·latency·비용 설명 가능 |
| 중간 | history policy 품질 검증 | 이전 장애 맥락이 단순 조회를 오염시키지 않음 |
| 중간 | no-tool/direct-answer fast path 판단 | 용어/문서 질의가 운영 API 없이 답변 |
| 낮음 | 설계 문서와 구현 차이 정리 | docs와 code path가 다시 어긋나지 않음 |

## 7. 개선 로드맵

| 단계 | 개선 항목 | 현재 판정 | 완료 기준 |
|---|---|---|---|
| 1 | 자연어 질의 실행 깊이 제어 | 기본 구현, 검증 필요 | 대표 질의별 agent/tool count 테스트 |
| 2 | Agent 실행 관측성 | schema/collector 구현, instrumentation 부분 | run_id로 호출량·latency·비용 설명 |
| 3 | Threshold governance | registry/API 구현, runtime 연결 부분 | 변경 근거·이력 조회와 runtime 사용 |
| 4 | Run 단위 재현성 | manifest 저장 구현 | snapshot 모델 강제와 재현 리허설 |
| 5 | 자동 rollback 실행 경로 | inverse rollback 구현 | Verifier 실패 후 runbook rollback 일반화 |
| 6 | RCA gold set·라벨링 | schema/API 구현 | 운영 UI와 30~50건 이상 평가셋 |
| 7 | RCA 성능 리포트 | script/module 기반 | AC@1/3/5, Avg@5, UNKNOWN 비율 monthly artifact |
| 8 | ECE 캘리브레이션 | module 기반 | confidence bin별 ECE report와 threshold 조정 |
| 9 | Online feedback·drift | event/report 구현 | dashboard와 재보정 trigger |
| 10 | 사용자 영향 SLI 정의 | 측정 API 구현 | baseline 기반 objective 확정 |
| 11 | SLO burn-rate 알림 | route/severity 저장 구현 | page는 사용자 영향 SLO 위반 중심으로 검증 |
| 12 | 인과/상관 증거 태그 | 필드와 일부 로직 구현 | 전체 evidence profile 태그 일관성 |
| 13 | KEDB형 카탈로그 확장 | schema/API/repository 구현 | RCA 결과와 owner/fix/재발 이력 자동 연결 |

## 8. 후속 과제

| 항목 | 대응 |
|---|---|
| Bifrost SLO 수치 확정 | 2~4주 baseline으로 freshness/latency/success/completeness objective 확정 |
| RCA gold set 확보 | 운영자가 확정한 `accepted_root_cause_id`, trigger, evidence 축적 |
| UNKNOWN 임계값 확정 | AC@k/ECE 결과로 threshold 재조정 |
| Threshold runtime 연결 | RCA/Spring threshold를 registry source of truth로 연결 |
| Agent 관측성 완성 | `called_tools`, `total_llm_calls`, `cost_by_stage`, `budget_used` 저장 완성 |
| Online drift 운영화 | UNKNOWN 비율, override 비율, confidence 분포, root cause 분포 기준 확정 |
| Static threshold fallback 검증 | SLO 측정 불가 시 page noise가 생기지 않는지 회귀 테스트 |

## 9. 인용 시 주의

아래 표현은 사용하지 않는다.

- "LLM이 로그를 읽고 자동으로 RCA를 확정한다."
- "NIST/ISO가 Bifrost의 특정 threshold를 요구한다."
- "consumer lag가 높으면 무조건 page다."
- "UNKNOWN은 실패다."
- "Graphical event model이 다른 인과기법보다 항상 우월하다."
- "Google 5대 근본원인 카테고리가 산업 표준 taxonomy다."

## 10. 주요 출처

| 영역 | 출처 |
|---|---|
| AI governance/versioning | NIST AI RMF, NIST AI 600-1, ISO/IEC 42001, MLflow Model Registry, Langfuse Prompt Management |
| SRE/운영 | Google SRE Book/Workbook, AWS OPS06-BP04, Argo Rollouts AnalysisRun, Datadog burn-rate alerting, PagerDuty severity, ITIL problem management |
| RCA/평가 | RCAEval, PACE-LM, ECE calibration, abstention survey, Self-RAG |
| Agent/tool control | OpenAI Agents SDK handoffs/guardrails, Anthropic tool-use, AutoGen termination, ReAct |

외부 문헌은 구현 정합성을 검증하는 보조 기준이다. 최종 문서 문장은 항상 현재 코드와 운영 데이터에 맞춰 조정한다.
